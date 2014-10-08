/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flume.sink.kite;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.reflect.ReflectDatumWriter;
import org.apache.commons.io.FileUtils;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.channel.MemoryChannel;
import org.apache.flume.conf.Configurables;
import org.apache.flume.event.SimpleEvent;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.kitesdk.data.Dataset;
import org.kitesdk.data.DatasetDescriptor;
import org.kitesdk.data.DatasetReader;
import org.kitesdk.data.Datasets;
import org.kitesdk.data.PartitionStrategy;
import org.kitesdk.data.View;

public class TestDatasetSink {

  public static final String FILE_REPO_URI = "repo:file:target/test-repo";
  public static final String DATASET_NAME = "test";
  public static final String FILE_DATASET_URI =
      "dataset:file:target/test-repo/" + DATASET_NAME;
  public static final File SCHEMA_FILE = new File("target/record-schema.avsc");
  public static final Schema RECORD_SCHEMA = new Schema.Parser().parse(
      "{\"type\":\"record\",\"name\":\"rec\",\"fields\":[" +
          "{\"name\":\"id\",\"type\":\"string\"}," +
          "{\"name\":\"msg\",\"type\":[\"string\",\"null\"]," +
              "\"default\":\"default\"}]}");
  public static final Schema COMPATIBLE_SCHEMA = new Schema.Parser().parse(
      "{\"type\":\"record\",\"name\":\"rec\",\"fields\":[" +
          "{\"name\":\"id\",\"type\":\"string\"}]}");
  public static final Schema INCOMPATIBLE_SCHEMA = new Schema.Parser().parse(
      "{\"type\":\"record\",\"name\":\"user\",\"fields\":[" +
          "{\"name\":\"username\",\"type\":\"string\"}]}");
  public static final DatasetDescriptor DESCRIPTOR = new DatasetDescriptor
      .Builder()
      .schema(RECORD_SCHEMA)
      .build();

  Context config = null;
  Channel in = null;
  List<GenericRecord> expected = null;
  private static final String DFS_DIR = "target/test/dfs";
  private static final String TEST_BUILD_DATA_KEY = "test.build.data";
  private static String oldTestBuildDataProp = null;

  @BeforeClass
  public static void saveSchema() throws IOException {
    oldTestBuildDataProp = System.getProperty(TEST_BUILD_DATA_KEY);
    System.setProperty(TEST_BUILD_DATA_KEY, DFS_DIR);
    FileWriter schema = new FileWriter(SCHEMA_FILE);
    schema.append(RECORD_SCHEMA.toString());
    schema.close();
  }

  @AfterClass
  public static void tearDownClass() {
    FileUtils.deleteQuietly(new File(DFS_DIR));
    if (oldTestBuildDataProp != null) {
      System.setProperty(TEST_BUILD_DATA_KEY, oldTestBuildDataProp);
    }
  }

  @Before
  public void setup() throws EventDeliveryException {
    Datasets.delete(FILE_DATASET_URI);
    Datasets.create(FILE_DATASET_URI, DESCRIPTOR);

    this.config = new Context();
    this.in = new MemoryChannel();
    Configurables.configure(in, config);

    config.put(DatasetSinkConstants.CONFIG_KITE_DATASET_URI, FILE_DATASET_URI);

    GenericRecordBuilder builder = new GenericRecordBuilder(RECORD_SCHEMA);
    expected = Lists.<GenericRecord>newArrayList(
        builder.set("id", "1").set("msg", "msg1").build(),
        builder.set("id", "2").set("msg", "msg2").build(),
        builder.set("id", "3").set("msg", "msg3").build());

    putToChannel(in, Iterables.transform(expected,
        new Function<GenericRecord, Event>() {
          private int i = 0;

          @Override
          public Event apply(@Nullable GenericRecord rec) {
            this.i += 1;
            boolean useURI = (i % 2) == 0;
            return event(rec, RECORD_SCHEMA, SCHEMA_FILE, useURI);
          }
        }));
  }

  @After
  public void teardown() {
    Datasets.delete(FILE_DATASET_URI);
  }

  @Test
  public void testOldConfig() throws EventDeliveryException {
    config.put(DatasetSinkConstants.CONFIG_KITE_DATASET_URI, null);
    config.put(DatasetSinkConstants.CONFIG_KITE_REPO_URI, FILE_REPO_URI);
    config.put(DatasetSinkConstants.CONFIG_KITE_DATASET_NAME, DATASET_NAME);

    DatasetSink sink = sink(in, config);

    // run the sink
    sink.start();
    sink.process();
    sink.stop();

    Assert.assertEquals(
        Sets.newHashSet(expected),
        read(Datasets.load(FILE_DATASET_URI)));
    Assert.assertEquals("Should have committed", 0, remaining(in));
  }

  @Test
  public void testDatasetUriOverridesOldConfig() throws EventDeliveryException {
    // CONFIG_KITE_DATASET_URI is still set, otherwise this will cause an error
    config.put(DatasetSinkConstants.CONFIG_KITE_REPO_URI, "bad uri");
    config.put(DatasetSinkConstants.CONFIG_KITE_DATASET_NAME, "");

    DatasetSink sink = sink(in, config);

    // run the sink
    sink.start();
    sink.process();
    sink.stop();

    Assert.assertEquals(
        Sets.newHashSet(expected),
        read(Datasets.load(FILE_DATASET_URI)));
    Assert.assertEquals("Should have committed", 0, remaining(in));
  }

  @Test
  public void testFileStore() throws EventDeliveryException {
    DatasetSink sink = sink(in, config);

    // run the sink
    sink.start();
    sink.process();
    sink.stop();

    Assert.assertEquals(
        Sets.newHashSet(expected),
        read(Datasets.load(FILE_DATASET_URI)));
    Assert.assertEquals("Should have committed", 0, remaining(in));
  }

  @Ignore("Ignore until Kite supports durable Parquet by default")
  @Test
  public void testParquetDataset() throws EventDeliveryException {
    Datasets.delete(FILE_DATASET_URI);
    Dataset<GenericRecord> created = Datasets.create(FILE_DATASET_URI,
        new DatasetDescriptor.Builder(DESCRIPTOR)
            .format("parquet")
            .build());

    DatasetSink sink = sink(in, config);

    // run the sink
    sink.start();
    sink.process();
    sink.stop();

    Assert.assertEquals(Sets.newHashSet(expected), read(created));
    Assert.assertEquals("Should have committed", 0, remaining(in));
  }

  @Test
  public void testParquetDatasetNotSupported() throws EventDeliveryException {
    Datasets.delete(FILE_DATASET_URI);
    Dataset<GenericRecord> created = Datasets.create(FILE_DATASET_URI,
        new DatasetDescriptor.Builder(DESCRIPTOR)
            .format("parquet")
            .build());

    assertThrows("Parquet is not supported", IllegalArgumentException.class,
        new Callable() {

      @Override
      public Object call() throws Exception {
        DatasetSink sink = sink(in, config);

        sink.start();
        sink.process();
        return sink;
      }
    });
  }

  @Test
  public void testPartitionedData() throws EventDeliveryException {
    URI partitionedUri = URI.create("dataset:file:target/test-repo/partitioned");
    try {
      Datasets.create(partitionedUri, new DatasetDescriptor.Builder(DESCRIPTOR)
          .partitionStrategy(new PartitionStrategy.Builder()
              .identity("id", 10) // partition by id
              .build())
          .build());

      config.put(DatasetSinkConstants.CONFIG_KITE_DATASET_URI,
          partitionedUri.toString());
      DatasetSink sink = sink(in, config);

      // run the sink
      sink.start();
      sink.process();
      sink.stop();

      Assert.assertEquals(
          Sets.newHashSet(expected),
          read(Datasets.load(partitionedUri)));
      Assert.assertEquals("Should have committed", 0, remaining(in));
    } finally {
      if (Datasets.exists(partitionedUri)) {
        Datasets.delete(partitionedUri);
      }
    }
  }

  @Test
  public void testMiniClusterStore()
      throws EventDeliveryException, IOException {
    // setup a minicluster
    MiniDFSCluster cluster = new MiniDFSCluster
        .Builder(new Configuration())
        .build();

    FileSystem dfs = cluster.getFileSystem();
    Configuration conf = dfs.getConf();

    URI hdfsUri = URI.create(
        "dataset:" + conf.get("fs.defaultFS") + "/tmp/repo" + DATASET_NAME);
    try {
      // create a repository and dataset in HDFS
      Datasets.create(hdfsUri, DESCRIPTOR);

      // update the config to use the HDFS repository
      config.put(DatasetSinkConstants.CONFIG_KITE_DATASET_URI, hdfsUri.toString());

      DatasetSink sink = sink(in, config);

      // run the sink
      sink.start();
      sink.process();
      sink.stop();

      Assert.assertEquals(
          Sets.newHashSet(expected),
          read(Datasets.load(hdfsUri)));
      Assert.assertEquals("Should have committed", 0, remaining(in));

    } finally {
      if (Datasets.exists(hdfsUri)) {
        Datasets.delete(hdfsUri);
      }
      cluster.shutdown();
    }
  }

  @Test
  public void testBatchSize() throws EventDeliveryException {
    DatasetSink sink = sink(in, config);

    // release one record per process call
    config.put("kite.batchSize", "2");
    Configurables.configure(sink, config);

    sink.start();
    sink.process(); // process the first and second
    sink.roll(); // roll at the next process call
    sink.process(); // roll and process the third
    Assert.assertEquals(
        Sets.newHashSet(expected.subList(0, 2)),
        read(Datasets.load(FILE_DATASET_URI)));
    Assert.assertEquals("Should have committed", 0, remaining(in));
    sink.roll(); // roll at the next process call
    sink.process(); // roll, the channel is empty
    Assert.assertEquals(
        Sets.newHashSet(expected),
        read(Datasets.load(FILE_DATASET_URI)));
    sink.stop();
  }

  @Test
  public void testTimedFileRolling()
      throws EventDeliveryException, InterruptedException {
    // use a new roll interval
    config.put("kite.rollInterval", "1"); // in seconds

    DatasetSink sink = sink(in, config);

    Dataset<GenericRecord> records = Datasets.load(FILE_DATASET_URI);

    // run the sink
    sink.start();
    sink.process();

    Assert.assertEquals("Should have committed", 0, remaining(in));

    Thread.sleep(1100); // sleep longer than the roll interval
    sink.process(); // rolling happens in the process method

    Assert.assertEquals(Sets.newHashSet(expected), read(records));

    // wait until the end to stop because it would close the files
    sink.stop();
  }

  @Test
  public void testCompatibleSchemas() throws EventDeliveryException {
    DatasetSink sink = sink(in, config);

    // add a compatible record that is missing the msg field
    GenericRecordBuilder compatBuilder = new GenericRecordBuilder(
        COMPATIBLE_SCHEMA);
    GenericData.Record compatibleRecord = compatBuilder.set("id", "0").build();

    // add the record to the incoming channel
    putToChannel(in, event(compatibleRecord, COMPATIBLE_SCHEMA, null, false));

    // the record will be read using the real schema, so create the expected
    // record using it, but without any data

    GenericRecordBuilder builder = new GenericRecordBuilder(RECORD_SCHEMA);
    GenericData.Record expectedRecord = builder.set("id", "0").build();
    expected.add(expectedRecord);

    // run the sink
    sink.start();
    sink.process();
    sink.stop();

    Assert.assertEquals(
        Sets.newHashSet(expected),
        read(Datasets.load(FILE_DATASET_URI)));
    Assert.assertEquals("Should have committed", 0, remaining(in));
  }

  @Test
  public void testIncompatibleSchemas() throws EventDeliveryException {
    final DatasetSink sink = sink(in, config);

    GenericRecordBuilder builder = new GenericRecordBuilder(
        INCOMPATIBLE_SCHEMA);
    GenericData.Record rec = builder.set("username", "koala").build();
    putToChannel(in, event(rec, INCOMPATIBLE_SCHEMA, null, false));

    // run the sink
    sink.start();
    assertThrows("Should fail", EventDeliveryException.class,
        new Callable() {
          @Override
          public Object call() throws EventDeliveryException {
            sink.process();
            return null;
          }
        });
    sink.stop();

    Assert.assertEquals("Should have rolled back",
        expected.size() + 1, remaining(in));
  }

  @Test
  public void testMissingSchema() throws EventDeliveryException {
    final DatasetSink sink = sink(in, config);

    Event badEvent = new SimpleEvent();
    badEvent.setHeaders(Maps.<String, String>newHashMap());
    badEvent.setBody(serialize(expected.get(0), RECORD_SCHEMA));
    putToChannel(in, badEvent);

    // run the sink
    sink.start();
    assertThrows("Should fail", EventDeliveryException.class,
        new Callable() {
          @Override
          public Object call() throws EventDeliveryException {
            sink.process();
            return null;
          }
        });
    sink.stop();

    Assert.assertEquals("Should have rolled back",
        expected.size() + 1, remaining(in));
  }

  public static DatasetSink sink(Channel in, Context config) {
    DatasetSink sink = new DatasetSink();
    sink.setChannel(in);
    Configurables.configure(sink, config);
    return sink;
  }

  public static <T> HashSet<T> read(View<T> view) {
    DatasetReader<T> reader = null;
    try {
      reader = view.newReader();
      return Sets.newHashSet(reader.iterator());
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }

  public static int remaining(Channel ch) throws EventDeliveryException {
    Transaction t = ch.getTransaction();
    try {
      t.begin();
      int count = 0;
      while (ch.take() != null) {
        count += 1;
      }
      t.commit();
      return count;
    } catch (Throwable th) {
      t.rollback();
      Throwables.propagateIfInstanceOf(th, Error.class);
      Throwables.propagateIfInstanceOf(th, EventDeliveryException.class);
      throw new EventDeliveryException(th);
    } finally {
      t.close();
    }
  }

  public static void putToChannel(Channel in, Event... records)
      throws EventDeliveryException {
    putToChannel(in, Arrays.asList(records));
  }

  public static void putToChannel(Channel in, Iterable<Event> records)
      throws EventDeliveryException {
    Transaction t = in.getTransaction();
    try {
      t.begin();
      for (Event record : records) {
        in.put(record);
      }
      t.commit();
    } catch (Throwable th) {
      t.rollback();
      Throwables.propagateIfInstanceOf(th, Error.class);
      Throwables.propagateIfInstanceOf(th, EventDeliveryException.class);
      throw new EventDeliveryException(th);
    } finally {
      t.close();
    }
  }

  public static Event event(
      Object datum, Schema schema, File file, boolean useURI) {
    Map<String, String> headers = Maps.newHashMap();
    if (useURI) {
      headers.put(DatasetSinkConstants.AVRO_SCHEMA_URL_HEADER,
          file.getAbsoluteFile().toURI().toString());
    } else {
      headers.put(DatasetSinkConstants.AVRO_SCHEMA_LITERAL_HEADER,
          schema.toString());
    }
    Event e = new SimpleEvent();
    e.setBody(serialize(datum, schema));
    e.setHeaders(headers);
    return e;
  }

  @SuppressWarnings("unchecked")
  public static byte[] serialize(Object datum, Schema schema) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Encoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    ReflectDatumWriter writer = new ReflectDatumWriter(schema);
    try {
      writer.write(datum, encoder);
      encoder.flush();
    } catch (IOException ex) {
      Throwables.propagate(ex);
    }
    return out.toByteArray();
  }

  /**
   * A convenience method to avoid a large number of @Test(expected=...) tests.
   *
   * This variant uses a Callable, which is allowed to throw checked Exceptions.
   *
   * @param message A String message to describe this assertion
   * @param expected An Exception class that the Runnable should throw
   * @param callable A Callable that is expected to throw the exception
   */
  public static void assertThrows(
      String message, Class<? extends Exception> expected, Callable callable) {
    try {
      callable.call();
      Assert.fail("No exception was thrown (" + message + "), expected: " +
          expected.getName());
    } catch (Exception actual) {
      Assert.assertEquals(message, expected, actual.getClass());
    }
  }
}
