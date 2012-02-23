/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.parse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.SignatureFactory;
import org.apache.nutch.metadata.Metadata;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.net.protocols.Response;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.scoring.ScoringFilterException;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.StringUtil;
import org.apache.nutch.util.TimingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* Parse content in a segment. */
public class ParseSegment extends Configured implements Tool,
    Mapper<WritableComparable, Content, Text, ParseImpl>,
    Reducer<Text, Writable, Text, Writable> {

  public static final Logger LOG = LoggerFactory.getLogger(ParseSegment.class);
  
  public static final String SKIP_TRUNCATED = "parser.skip.truncated";
  
  private ScoringFilters scfilters;
  
  private boolean skipTruncated;
  
  public ParseSegment() {
    this(null);
  }
  
  public ParseSegment(Configuration conf) {
    super(conf);
  }

  public void configure(JobConf job) {
    setConf(job);
    this.scfilters = new ScoringFilters(job);
    skipTruncated=job.getBoolean(SKIP_TRUNCATED, true);
  }

  public void close() {}
  
  private Text newKey = new Text();

  public void map(WritableComparable key, Content content,
                  OutputCollector<Text, ParseImpl> output, Reporter reporter)
    throws IOException {
    // convert on the fly from old UTF8 keys
    if (key instanceof UTF8) {
      newKey.set(key.toString());
      key = newKey;
    }
    
    int status =
      Integer.parseInt(content.getMetadata().get(Nutch.FETCH_STATUS_KEY));
    if (status != CrawlDatum.STATUS_FETCH_SUCCESS) {
      // content not fetched successfully, skip document
      LOG.debug("Skipping " + key + " as content is not fetched successfully");
      return;
    }
    
    if (skipTruncated && isTruncated(content)) {
      return;
    }

    ParseResult parseResult = null;
    try {
      parseResult = new ParseUtil(getConf()).parse(content);
    } catch (Exception e) {
      LOG.warn("Error parsing: " + key + ": " + StringUtils.stringifyException(e));
      return;
    }

    for (Entry<Text, Parse> entry : parseResult) {
      Text url = entry.getKey();
      Parse parse = entry.getValue();
      ParseStatus parseStatus = parse.getData().getStatus();

      long start = System.currentTimeMillis();

      reporter.incrCounter("ParserStatus", ParseStatus.majorCodes[parseStatus.getMajorCode()], 1);

      if (!parseStatus.isSuccess()) {
        LOG.warn("Error parsing: " + key + ": " + parseStatus);
        parse = parseStatus.getEmptyParse(getConf());
      }

      // pass segment name to parse data
      parse.getData().getContentMeta().set(Nutch.SEGMENT_NAME_KEY, 
                                           getConf().get(Nutch.SEGMENT_NAME_KEY));

      // compute the new signature
      byte[] signature = 
        SignatureFactory.getSignature(getConf()).calculate(content, parse); 
      parse.getData().getContentMeta().set(Nutch.SIGNATURE_KEY, 
          StringUtil.toHexString(signature));
      
      try {
        scfilters.passScoreAfterParsing(url, content, parse);
      } catch (ScoringFilterException e) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("Error passing score: "+ url +": "+e.getMessage());
        }
      }

      long end = System.currentTimeMillis();
      LOG.info("Parsed (" + Long.toString(end - start) + "ms):" + url);

      output.collect(url, new ParseImpl(new ParseText(parse.getText()), 
                                        parse.getData(), parse.isCanonical()));
    }
  }
  
  /**
   * Checks if the page's content is truncated.
   * @param content
   * @return If the page is truncated <code>true</code>. When it is not,
   * or when it could be determined, <code>false</code>. 
   */
  public static boolean isTruncated(Content content) {
    byte[] contentBytes = content.getContent();
    if (contentBytes == null) return false;
    Metadata metadata = content.getMetadata();
    if (metadata == null) return false;
    
    String lengthStr = metadata.get(Response.CONTENT_LENGTH);
    if (lengthStr != null) lengthStr=lengthStr.trim();
    if (StringUtil.isEmpty(lengthStr)) {
      return false;
    }
    int inHeaderSize;
    String url = content.getUrl();
    try {
      inHeaderSize = Integer.parseInt(lengthStr);
    } catch (NumberFormatException e) {
      LOG.warn("Wrong contentlength format for " + url, e);
      return false;
    }
    int actualSize = contentBytes.length;
    if (inHeaderSize > actualSize) {
      LOG.info(url + " skipped. Content of size " + inHeaderSize
          + " was truncated to " + actualSize);
      return true;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(url + " actualSize=" + actualSize + " inHeaderSize=" + inHeaderSize);
    }
    return false;
  }

  public void reduce(Text key, Iterator<Writable> values,
                     OutputCollector<Text, Writable> output, Reporter reporter)
    throws IOException {
    output.collect(key, (Writable)values.next()); // collect first value
  }

  public void parse(Path segment) throws IOException {

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    long start = System.currentTimeMillis();
    if (LOG.isInfoEnabled()) {
      LOG.info("ParseSegment: starting at " + sdf.format(start));
      LOG.info("ParseSegment: segment: " + segment);
    }

    JobConf job = new NutchJob(getConf());
    job.setJobName("parse " + segment);

    FileInputFormat.addInputPath(job, new Path(segment, Content.DIR_NAME));
    job.set(Nutch.SEGMENT_NAME_KEY, segment.getName());
    job.setInputFormat(SequenceFileInputFormat.class);
    job.setMapperClass(ParseSegment.class);
    job.setReducerClass(ParseSegment.class);
    
    FileOutputFormat.setOutputPath(job, segment);
    job.setOutputFormat(ParseOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(ParseImpl.class);

    JobClient.runJob(job);
    long end = System.currentTimeMillis();
    LOG.info("ParseSegment: finished at " + sdf.format(end) + ", elapsed: " + TimingUtil.elapsedTime(start, end));
  }


  public static void main(String[] args) throws Exception {
	int res = ToolRunner.run(NutchConfiguration.create(), new ParseSegment(), args);
	System.exit(res);
  }
	  
  public int run(String[] args) throws Exception {
    Path segment;

    String usage = "Usage: ParseSegment segment [-noFilter] [-noNormalize]";

    if (args.length == 0) {
      System.err.println(usage);
      System.exit(-1);
    }

    if(args.length > 1) {
      for(int i = 1; i < args.length; i++) {
        String param = args[i];

        if("-noilter".equalsIgnoreCase(param)) {
          getConf().setBoolean("parse.filter.urls", false);
        } else if ("-nonormalize".equalsIgnoreCase(param)) {
          getConf().setBoolean("parse.normalize.urls", false);
        }
      }
    }

    segment = new Path(args[0]);
    parse(segment);
    return 0;
  }
}
