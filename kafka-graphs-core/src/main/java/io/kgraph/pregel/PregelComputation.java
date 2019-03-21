/*
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

package io.kgraph.pregel;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.nodes.GroupMember;
import org.apache.curator.framework.recipes.shared.SharedValue;
import org.apache.curator.utils.ZKPaths;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.internals.AbstractTask;
import org.apache.kafka.streams.processor.internals.ProcessorContextImpl;
import org.apache.kafka.streams.processor.internals.StreamTask;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kgraph.EdgeWithValue;
import io.kgraph.GraphAlgorithmState.State;
import io.kgraph.GraphSerialized;
import io.kgraph.VertexWithValue;
import io.kgraph.pregel.PregelState.Stage;
import io.kgraph.pregel.aggregators.Aggregator;
import io.kgraph.utils.ClientUtils;
import io.kgraph.utils.KryoSerde;
import io.kgraph.utils.KryoSerializer;
import io.kgraph.utils.KryoUtils;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import io.vavr.Tuple4;

public class PregelComputation<K, VV, EV, Message> implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(PregelComputation.class);

    private static final String LAST_WRITTEN_OFFSETS = "last.written.offsets";

    private final String hostAndPort;
    private final String applicationId;
    private final String bootstrapServers;
    private final CuratorFramework curator;

    private final String verticesTopic;
    private KTable<K, VV> vertices;

    private final String edgesGroupedBySourceTopic;
    private KTable<K, Map<K, EV>> edgesGroupedBySource;

    private final String solutionSetTopic;
    private final String solutionSetStore;
    private KTable<K, VV> solutionSet;

    private final String workSetTopic;
    private KStream<K, Tuple3<Integer, K, List<Message>>> workSet;

    private final int numPartitions;

    private final GraphSerialized<K, VV, EV> serialized;

    private final Map<String, ?> configs;
    private final Optional<Message> initialMessage;
    private final ComputeFunction<K, VV, EV, Message> computeFunction;
    private final Map<String, AggregatorWrapper<?>> registeredAggregators;

    private Properties streamsConfig;

    private volatile int maxIterations = Integer.MAX_VALUE;
    private volatile CompletableFuture<KTable<K, VV>> futureResult;

    private final String edgesStoreName;
    private final String verticesStoreName;
    private final String localworkSetStoreName;
    private final String localSolutionSetStoreName;

    private final Map<Integer, Map<Integer, Set<K>>> activeVertices = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Boolean>> didPreSuperstep = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Long>> lastWrittenOffsets = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Map<String, Aggregator<?>>>> aggregators = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, ?>> previousAggregates = new ConcurrentHashMap<>();

    public PregelComputation(
        String hostAndPort,
        String applicationId,
        String bootstrapServers,
        CuratorFramework curator,
        String verticesTopic,
        String edgesGroupedBySourceTopic,
        GraphSerialized<K, VV, EV> serialized,
        String solutionSetTopic,
        String solutionSetStore,
        String workSetTopic,
        int numPartitions,
        Map<String, ?> configs,
        Optional<Message> initialMessage,
        ComputeFunction<K, VV, EV, Message> cf
    ) {

        this.hostAndPort = hostAndPort;
        this.applicationId = applicationId;
        this.bootstrapServers = bootstrapServers;
        this.curator = curator;
        this.verticesTopic = verticesTopic;
        this.edgesGroupedBySourceTopic = edgesGroupedBySourceTopic;
        this.solutionSetStore = solutionSetStore;
        this.solutionSetTopic = solutionSetTopic;
        this.workSetTopic = workSetTopic;
        this.numPartitions = numPartitions;
        this.serialized = serialized;
        this.configs = configs;
        this.initialMessage = initialMessage;
        this.computeFunction = cf;
        this.registeredAggregators = new ConcurrentHashMap<>();

        this.edgesStoreName = "edgesStore-" + applicationId;
        this.verticesStoreName = "verticesStore-" + applicationId;
        this.localworkSetStoreName = "localworkSetStore-" + applicationId;
        this.localSolutionSetStoreName = "localSolutionSetStore-" + applicationId;

        ComputeFunction.InitCallback cb = new ComputeFunction.InitCallback(registeredAggregators);
        cf.init(configs, new ComputeFunction.InitCallback(registeredAggregators));
        cb.registerAggregator(LAST_WRITTEN_OFFSETS, MapOfLongMaxAggregator.class);
    }

    public KTable<K, VV> vertices() {
        return vertices;
    }

    public KTable<K, Map<K, EV>> edgesGroupedBySource() {
        return edgesGroupedBySource;
    }

    public KTable<K, VV> result() {
        return solutionSet;
    }

    public CompletableFuture<KTable<K, VV>> futureResult() {
        return futureResult;
    }

    public void prepare(StreamsBuilder builder, Properties streamsConfig) {
        this.streamsConfig = streamsConfig;

        final StoreBuilder<KeyValueStore<Integer, Map<K, Map<K, List<Message>>>>> workSetStoreBuilder =
            Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(localworkSetStoreName),
                Serdes.Integer(), new KryoSerde<>()
            );
        builder.addStateStore(workSetStoreBuilder);

        final StoreBuilder<KeyValueStore<K, Tuple4<Integer, VV, Integer, VV>>> solutionSetStoreBuilder =
            Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(localSolutionSetStoreName),
                serialized.keySerde(), new KryoSerde<>()
            );
        builder.addStateStore(solutionSetStoreBuilder);

        this.vertices = builder
            .table(
                verticesTopic,
                Materialized.<K, VV, KeyValueStore<Bytes, byte[]>>as(verticesStoreName)
                    .withKeySerde(serialized.keySerde()).withValueSerde(serialized.vertexValueSerde())
            );

        this.edgesGroupedBySource = builder
            .table(
                edgesGroupedBySourceTopic,
                Materialized.<K, Map<K, EV>, KeyValueStore<Bytes, byte[]>>as(edgesStoreName)
                    .withKeySerde(serialized.keySerde()).withValueSerde(new KryoSerde<>())
            );

        this.solutionSet = builder
            .table(solutionSetTopic, Consumed.<K, Tuple4<Integer, VV, Integer, VV>>with(serialized.keySerde(), new KryoSerde<>()))
            .mapValues(v -> v._4, Materialized.as(solutionSetStore));

        // Initalize solution set
        this.vertices
            .toStream()
            .mapValues(v -> new Tuple4<>(-1, v, 0, v))
            .to(solutionSetTopic, Produced.with(serialized.keySerde(), new KryoSerde<>()));

        // Initialize workset
        this.vertices
            .toStream()
            .peek((k, v) -> {
                try {
                    int partition = PregelComputation.vertexToPartition(k, serialized.keySerde().serializer(), numPartitions);
                    ZKUtils.addChild(curator, applicationId, new PregelState(State.CREATED, 0, Stage.SEND), "partition-" + partition);
                } catch (Exception e) {
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }

            })
            .mapValues((k, v) -> new Tuple3<>(0, k, initialMessage.map(Collections::singletonList).orElse(Collections.emptyList())))
            .peek((k, v) -> log.trace("workset 0 before topic: (" + k + ", " + v + ")"))
            .<K, Tuple3<Integer, K, List<Message>>>to(workSetTopic, Produced.with(serialized.keySerde(), new KryoSerde<>
                ()));

        this.workSet = builder
            .stream(workSetTopic, Consumed.with(serialized.keySerde(), new KryoSerde<Tuple3<Integer, K, List<Message>>>()))
            .peek((k, v) -> log.trace("workset 1 after topic: (" + k + ", " + v + ")"))
            // 0th iteration does not count as it just sets up the initial message
            .filter((K k, Tuple3<Integer, K, List<Message>> v) -> v._1 <= maxIterations);

        KStream<K, Tuple2<Integer, Map<K, List<Message>>>> syncedWorkSet = workSet
            .transform(BarrierSync::new, localworkSetStoreName)
            .peek((k, v) -> log.trace("workset 2 after join: (" + k + ", " + v + ")"));

        KStream<K, Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, List<Message>>>> superstepComputation =
            syncedWorkSet
                .transformValues(VertexComputeUdf::new, localSolutionSetStoreName, vertices.queryableStoreName(),
                    edgesGroupedBySource.queryableStoreName());

        // Compute the solution set delta
        KStream<K, Tuple4<Integer, VV, Integer, VV>> solutionSetDelta = superstepComputation
            .flatMapValues(v -> v._2 != null ? Collections.singletonList(v._2) : Collections.emptyList())
            .peek((k, v) -> log.trace("solution set: (" + k + ", " + v + ")"));

        solutionSetDelta
            .to(solutionSetTopic, Produced.with(serialized.keySerde(), new KryoSerde<>()));

        // Compute the inbox of each vertex for the next step (new workset)
        KStream<K, Tuple2<Integer, Map<K, List<Message>>>> newworkSet = superstepComputation
            .mapValues(v -> new Tuple2<>(v._1, v._3))
            .peek((k, v) -> log.trace("workset new: (" + k + ", " + v + ")"));

        newworkSet.process(SendMessages::new);
    }

    public PregelState run(int maxIterations, CompletableFuture<KTable<K, VV>> futureResult) {
        this.maxIterations = maxIterations;
        this.futureResult = futureResult;

        PregelState pregelState = new PregelState(State.RUNNING, -1, Stage.SEND);
        try (SharedValue sharedValue = new SharedValue(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.SUPERSTEP), pregelState.toBytes())) {
            sharedValue.start();
            setPregelState(sharedValue, pregelState);
            return pregelState;
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    public PregelState state() {
        PregelState pregelState = new PregelState(State.RUNNING, -1, Stage.SEND);
        try (SharedValue sharedValue = new SharedValue(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.SUPERSTEP), pregelState.toBytes())) {
            sharedValue.start();
            pregelState = PregelState.fromBytes(sharedValue.getValue());
            return pregelState;
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    protected Map<String, Aggregator<?>> newAggregators() {
        Set<Map.Entry<String, AggregatorWrapper<?>>> entries = registeredAggregators.entrySet();
        return entries.stream()
            .collect(Collectors.toConcurrentMap(Map.Entry::getKey, entry -> {
                try {
                    return ClientUtils.getConfiguredInstance(entry.getValue().getAggregatorClass(), configs);
                } catch (Exception e) {
                    throw toRuntimeException(e);
                }
            }));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Aggregator<?>> initAggregators(Map<String, Aggregator<?>> agg, Map<String, ?> values) {
        return agg.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                Aggregator<Object> a = (Aggregator<Object>) e.getValue();
                Object value = values.get(e.getKey());
                if (value != null && registeredAggregators.get(e.getKey()).isPersistent()) {
                    a.aggregate(value);
                }
                return a;
            }));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Aggregator<?>> mergeAggregators(Map<String, Aggregator<?>> agg1, Map<String, Aggregator<?>> agg2) {
        return Stream.of(agg1, agg2).map(Map::entrySet).flatMap(Collection::stream).collect(
            Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                Aggregator<Object> a1 = (Aggregator<Object>) v1;
                Aggregator<Object> a2 = (Aggregator<Object>) v2;
                a1.aggregate(a2.getAggregate());
                return a1;
            }));
    }

    private Map<String, ?> previousAggregates(int superstep) {
        return previousAggregates.computeIfAbsent(superstep, k -> {
            try {
                String path = ZKPaths.makePath(ZKUtils.aggregatePath(applicationId, superstep - 1), "all");
                if (curator.checkExists().forPath(path) == null) {
                    return new HashMap<>();
                }
                byte[] data = curator.getData().forPath(path);
                return data.length > 0 ? KryoUtils.deserialize(data) : new HashMap<>();
            } catch (Exception e) {
                throw toRuntimeException(e);
            }
        });
    }

    private Map<String, Aggregator<?>> aggregators(int partition, int superstep) {
        Map<Integer, Map<String, Aggregator<?>>> stepAggregators =
            aggregators.computeIfAbsent(superstep, k -> new ConcurrentHashMap<>());
        return stepAggregators.computeIfAbsent(partition, k -> newAggregators());
    }

    private final class BarrierSync
        implements Transformer<K, Tuple3<Integer, K, List<Message>>,
        KeyValue<K, Tuple2<Integer, Map<K, List<Message>>>>> {

        private ProcessorContext context;
        private KeyValueStore<Integer, Map<K, Map<K, List<Message>>>> localworkSetStore;
        private Consumer<byte[], byte[]> internalConsumer;
        private LeaderLatch leaderLatch;
        private GroupMember group;
        private SharedValue sharedValue;
        private TreeCache aggregateCache;
        private TreeCache barrierCache;
        private PregelState pregelState = new PregelState(State.CREATED, -1, Stage.SEND);

        private final Map<Integer, Set<K>> forwardedVertices = new HashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public void init(final ProcessorContext context) {
            try {
                this.context = context;
                this.localworkSetStore = (KeyValueStore<Integer, Map<K, Map<K, List<Message>>>>) context.getStateStore(localworkSetStoreName);
                this.internalConsumer = internalConsumer(context);

                String threadId = String.valueOf(Thread.currentThread().getId());
                // Worker name needs to be unique to a StreamThread but common to StreamTasks that share a StreamThread
                String workerName = hostAndPort != null ? hostAndPort + "#" + threadId : "local:#" + threadId;
                log.debug("Registering worker {} for application {}", workerName, applicationId);
                group = new GroupMember(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.GROUP), workerName);
                group.start();
                leaderLatch = new LeaderLatch(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.LEADER));
                leaderLatch.start();
                sharedValue = new SharedValue(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.SUPERSTEP), pregelState.toBytes());
                sharedValue.start();

                // TODO make interval configurable
                this.context.schedule(Duration.ofMillis(500), PunctuationType.WALL_CLOCK_TIME, (timestamp) -> {
                    try {
                        pregelState = PregelState.fromBytes(sharedValue.getValue());
                        State state = pregelState.state();

                        if (state == State.CREATED) {
                            return;
                        } else if (state == State.COMPLETED || state == State.CANCELLED) {
                            if (futureResult != null && !futureResult.isDone()) {
                                if (pregelState.superstep() > maxIterations || state == State.CANCELLED) {
                                    log.info("Pregel computation halted after {} iterations", pregelState.superstep());
                                } else {
                                    log.info("Pregel computation converged after {} iterations", pregelState.superstep());
                                }
                                this.context.commit();
                                futureResult.complete(result());
                            }
                            return;
                        }

                        if (leaderLatch.hasLeadership()) {
                            if (aggregateCache == null) {
                                aggregateCache = new TreeCache(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.AGGREGATES));
                                aggregateCache.start();
                            }
                            if (barrierCache == null) {
                                barrierCache = new TreeCache(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.BARRIERS));
                                barrierCache.start();
                            }

                            if (pregelState.stage() == Stage.RECEIVE) {
                                int groupSize = group.getCurrentMembers().size();
                                PregelState nextPregelState = ZKUtils.maybeCreateReadyToSendNode(curator, applicationId, pregelState, barrierCache, groupSize);
                                if (!pregelState.equals(nextPregelState)) {
                                    pregelState = nextPregelState;
                                    setPregelState(sharedValue, pregelState);
                                } else {
                                    log.debug("Not ready to create snd: state {}", pregelState);
                                }
                            } else if (pregelState.stage() == Stage.SEND) {
                                PregelState nextPregelState = ZKUtils.maybeCreateReadyToReceiveNode(curator, applicationId, pregelState, barrierCache);
                                if (!pregelState.equals(nextPregelState)) {
                                    pregelState = nextPregelState;
                                    setPregelState(sharedValue, pregelState);
                                    boolean halt = masterCompute(pregelState.superstep());
                                    if (halt) {
                                        pregelState = pregelState.state(State.CANCELLED);
                                        setPregelState(sharedValue, pregelState);
                                    }
                                } else {
                                    log.debug("Not ready to create rcv: state {}", pregelState);
                                }
                            }
                            if (pregelState.superstep() > maxIterations) {
                                pregelState = pregelState.state(State.COMPLETED);
                                setPregelState(sharedValue, pregelState);
                                return;
                            }
                        }

                        if (pregelState.stage() == Stage.RECEIVE) {
                            if (pregelState.superstep() == 0) {
                                if (!ZKUtils.hasChild(curator, applicationId, pregelState, workerName)) {
                                    Set<TopicPartition> workSetTps = localPartitions(internalConsumer, workSetTopic);
                                    Set<TopicPartition> solutionSetTps = localPartitions(internalConsumer, solutionSetTopic);
                                    if (isTopicSynced(internalConsumer, verticesTopic, 0, null)
                                        && isTopicSynced(internalConsumer, edgesGroupedBySourceTopic, 0, null)) {
                                        ZKUtils.addChild(curator, applicationId, pregelState, workerName, CreateMode.EPHEMERAL);
                                        // Ensure vertices and edges are read into tables first
                                        internalConsumer.seekToBeginning(workSetTps);
                                        internalConsumer.resume(workSetTps);
                                        internalConsumer.seekToBeginning(solutionSetTps);
                                        internalConsumer.resume(solutionSetTps);
                                    } else {
                                        internalConsumer.pause(workSetTps);
                                        internalConsumer.pause(solutionSetTps);
                                    }
                                }
                            }
                            if (ZKUtils.isReady(curator, applicationId, pregelState)) {
                                if (!ZKUtils.hasChild(curator, applicationId, pregelState, workerName)) {
                                    // Try to ensure we have all messages; however the consumer may not yet
                                    // be in sync so we do another check in the next stage
                                    Map<Integer, Long> lastWrittenOffsets =
                                        (Map<Integer, Long>) previousAggregates(pregelState.superstep()).get(LAST_WRITTEN_OFFSETS);
                                    if (isTopicSynced(internalConsumer, workSetTopic, pregelState.superstep(), lastWrittenOffsets)) {
                                        ZKUtils.addChild(curator, applicationId, pregelState, workerName, CreateMode.EPHEMERAL);
                                    }
                                }
                            }
                        } else if (pregelState.stage() == Stage.SEND) {
                            if (ZKUtils.isReady(curator, applicationId, pregelState)) {
                                Map<K, Map<K, List<Message>>> messages = localworkSetStore.get(pregelState.superstep());
                                if (hasVerticesToForward(messages)) {
                                    // This check is to ensure we have all messages produced in the last stage;
                                    // we may get new messages as well but that is fine
                                    Map<Integer, Long> lastWrittenOffsets =
                                        (Map<Integer, Long>) previousAggregates(pregelState.superstep()).get(LAST_WRITTEN_OFFSETS);
                                    if (isTopicSynced(internalConsumer, workSetTopic, pregelState.superstep(), lastWrittenOffsets)) {
                                        forwardVertices(messages);
                                    }
                                }

                                // clean up previous step
                                int previousStep = pregelState.superstep() - 1;
                                activeVertices.remove(previousStep);
                                forwardedVertices.remove(previousStep);
                                didPreSuperstep.remove(previousStep);
                                aggregators.remove(previousStep);
                                previousAggregates.remove(previousStep);
                                localworkSetStore.delete(previousStep);
                            }
                        }
                    } catch (Exception e) {
                        throw toRuntimeException(e);
                    }
                });
            } catch (Exception e) {
                throw toRuntimeException(e);
            }
        }

        private boolean masterCompute(int superstep) throws Exception {
            // Collect aggregator values, then run the masterCompute() and
            // finally save the aggregator values
            Map<String, Aggregator<?>> newAggregators = reduceAggregates(superstep - 1);
            ComputeFunction.MasterCallback cb = new ComputeFunction.MasterCallback(newAggregators);
            computeFunction.masterCompute(superstep, cb);
            saveAggregates(superstep - 1, newAggregators);
            return cb.haltComputation;
        }

        private Map<String, Aggregator<?>> reduceAggregates(int superstep) {
            String rootPath = ZKUtils.aggregatePath(applicationId, superstep);
            Map<String, Aggregator<?>> newAggregators = newAggregators();
            newAggregators = initAggregators(newAggregators, previousAggregates(superstep));
            Map<String, ChildData> children = aggregateCache.getCurrentChildren(rootPath);
            if (children != null) {
                for (Map.Entry<String, ChildData> entry : children.entrySet()) {
                    ChildData childData = entry.getValue();
                    String path = childData.getPath();
                    if (!path.endsWith("all")) {
                        byte[] data = childData.getData();
                        if (data.length > 0) {
                            Map<String, Aggregator<?>> aggregators = KryoUtils.deserialize(data);
                            newAggregators = mergeAggregators(newAggregators, aggregators);
                        }
                    }
                }
            }
            return newAggregators;
        }

        private void saveAggregates(int superstep, Map<String, Aggregator<?>> newAggregators) throws Exception {
            String rootPath = ZKUtils.aggregatePath(applicationId, superstep);
            Set<Map.Entry<String, Aggregator<?>>> entries = newAggregators.entrySet();
            Map<String, ?> newAggregates = entries.stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getAggregate()));
            ZKUtils.addChild(curator, rootPath, "all", CreateMode.PERSISTENT, KryoUtils.serialize(newAggregates));
        }

        private boolean hasVerticesToForward(Map<K, Map<K, List<Message>>> messages) {
            if (messages == null) return false;
            for (Map.Entry<K, Map<K, List<Message>>> entry : messages.entrySet()) {
                Set<K> forwarded = forwardedVertices.get(pregelState.superstep());
                if (forwarded == null || !forwarded.contains(entry.getKey())) {
                    return true;
                }
            }
            return false;
        }

        private void forwardVertices(Map<K, Map<K, List<Message>>> messages) {
            List<Map.Entry<K, Map<K, List<Message>>>> toForward = new ArrayList<>();
            for (Map.Entry<K, Map<K, List<Message>>> entry : messages.entrySet()) {
                Set<K> forwarded = forwardedVertices.computeIfAbsent(pregelState.superstep(), k -> new HashSet<>());
                if (!forwarded.contains(entry.getKey())) {
                    forwarded.add(entry.getKey());
                    activateVertex(entry);
                    toForward.add(entry);
                }
            }
            for (Map.Entry<K, Map<K, List<Message>>> entry : toForward) {
                context.forward(entry.getKey(), new Tuple2<>(pregelState.superstep(), entry.getValue()));
            }
            context.commit();
        }

        private void activateVertex(Map.Entry<K, Map<K, List<Message>>> entry) {
            int partition = vertexToPartition(entry.getKey(), serialized.keySerde().serializer(), numPartitions);
            Map<Integer, Set<K>> active = activeVertices.computeIfAbsent(
                pregelState.superstep(), k -> new ConcurrentHashMap<>());
            Set<K> vertices = active.computeIfAbsent(partition, k -> ConcurrentHashMap.newKeySet());
            vertices.add(entry.getKey());
            log.debug("vertex {} for partition {} for step {} is active", entry.getKey(), partition, pregelState.superstep());
        }

        @Override
        public KeyValue<K, Tuple2<Integer, Map<K, List<Message>>>> transform(
            final K readOnlyKey, final Tuple3<Integer, K, List<Message>> value
        ) {
            Map<K, Map<K, List<Message>>> messages = localworkSetStore.get(value._1);
            if (messages == null) {
                messages = new HashMap<>();
            }
            Map<K, List<Message>> messagesForSuperstep = messages.computeIfAbsent(readOnlyKey, k -> new HashMap<>());
            if (value._3 != null) {
                messagesForSuperstep.put(value._2, value._3);
            }
            localworkSetStore.put(value._1, messages);

            Set<K> forwarded = forwardedVertices.get(value._1);
            if (forwarded != null) {
                forwarded.remove(readOnlyKey);
            }

            return null;
        }

        @Override
        public void close() {
            if (aggregateCache != null) {
                aggregateCache.close();
            }
            if (barrierCache != null) {
                barrierCache.close();
            }
            if (sharedValue != null) {
                try {
                    sharedValue.close();
                } catch (Exception e) {
                    // ignore
                }
            }
            if (leaderLatch != null) {
                try {
                    leaderLatch.close();
                } catch (Exception e) {
                    // ignore
                }
            }
            if (group != null) {
                group.close();
            }
        }
    }

    private final class VertexComputeUdf
        implements ValueTransformerWithKey<K, Tuple2<Integer, Map<K, List<Message>>>,
        Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, List<Message>>>> {

        private KeyValueStore<K, Tuple4<Integer, VV, Integer, VV>> localSolutionSetStore;
        private ReadOnlyKeyValueStore<K, VV> verticesStore;
        private KeyValueStore<K, Map<K, EV>> edgesStore;

        @SuppressWarnings("unchecked")
        @Override
        public void init(final ProcessorContext context) {
            this.localSolutionSetStore = (KeyValueStore<K, Tuple4<Integer, VV, Integer, VV>>) context.getStateStore(localSolutionSetStoreName);
            this.verticesStore = (ReadOnlyKeyValueStore<K, VV>) context.getStateStore(vertices.queryableStoreName());
            this.edgesStore = (KeyValueStore<K, Map<K, EV>>) context.getStateStore(edgesGroupedBySource.queryableStoreName());
        }

        @Override
        public Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, List<Message>>> transform(
            final K readOnlyKey, final Tuple2<Integer, Map<K, List<Message>>> value
        ) {
            int superstep = value._1;
            Tuple4<Integer, VV, Integer, VV> vertex = localSolutionSetStore.get(readOnlyKey);
            if (vertex == null) {
                VV vertexValue = verticesStore.get(readOnlyKey);
                if (vertexValue == null) {
                    log.warn("No vertex value for {}", readOnlyKey);
                }
                vertex = new Tuple4<>(-1, vertexValue, 0, vertexValue);
            }
            Map<K, List<Message>> messages = value._2;
            Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, List<Message>>> result =
                apply(superstep, readOnlyKey, vertex, messages);
            if (result._2 != null) {
                localSolutionSetStore.put(readOnlyKey, result._2);
            }
            return result;
        }

        private Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, List<Message>>> apply(
            int superstep,
            K key,
            Tuple4<Integer, VV, Integer, VV> vertex,
            Map<K, List<Message>> incomingMessages
        ) {
            // Find the value that applies to this step
            VV oldVertexValue = vertex._3 <= superstep ? vertex._4 : vertex._2;
            int partition = vertexToPartition(key, serialized.keySerde().serializer(), numPartitions);

            Map<Integer, Boolean> didFlags = didPreSuperstep.computeIfAbsent(superstep, k -> new ConcurrentHashMap<>());
            Boolean flag = didFlags.get(partition);
            if (flag == null || !flag) {
                ComputeFunction.Aggregators aggregators = new ComputeFunction.Aggregators(
                    previousAggregates(superstep), aggregators(partition, superstep));
                computeFunction.preSuperstep(superstep, aggregators);
                didFlags.put(partition, true);
            }

            ComputeFunction.Callback<K, VV, EV, Message> cb = new ComputeFunction.Callback<>(key, edgesStore,
                previousAggregates(superstep), aggregators(partition, superstep));
            Iterable<Message> messages = () -> incomingMessages.values().stream()
                .flatMap(List::stream)
                .iterator();
            Iterable<EdgeWithValue<K, EV>> edges = () -> {
                Map<K, EV> outgoingEdges = edgesStore.get(key);
                if (outgoingEdges == null) {
                    outgoingEdges = Collections.emptyMap();
                }
                return outgoingEdges.entrySet().stream()
                    .map(e -> new EdgeWithValue<>(key, e.getKey(), e.getValue()))
                    .iterator();
            };
            computeFunction.compute(superstep, new VertexWithValue<>(key, oldVertexValue), messages, edges, cb);
            Tuple4<Integer, VV, Integer, VV> newVertex = cb.newVertexValue != null
                ? new Tuple4<>(superstep, oldVertexValue, superstep + 1, cb.newVertexValue) : null;
            Map<K, List<Message>> outgoingMessages = cb.outgoingMessages;
            if (!cb.voteToHalt) {
                // Send to self to keep active
                outgoingMessages.computeIfAbsent(key, k -> new ArrayList<>());
            }
            return new Tuple3<>(superstep + 1, newVertex, outgoingMessages);
        }

        @Override
        public void close() {
        }
    }

    private final class SendMessages implements Processor<K, Tuple2<Integer, Map<K, List<Message>>>> {

        private Producer<K, Tuple3<Integer, K, List<Message>>> producer;

        @Override
        public void init(final ProcessorContext context) {
            Properties producerConfig = ClientUtils.producerConfig(
                bootstrapServers, serialized.keySerde().serializer().getClass(), KryoSerializer.class,
                streamsConfig != null ? streamsConfig : new Properties()
            );
            String clientId = "pregel-" + context.taskId();
            producerConfig.setProperty(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-producer");
            this.producer = new KafkaProducer<>(producerConfig);
        }

        @Override
        public void process(final K readOnlyKey, final Tuple2<Integer, Map<K, List<Message>>> value) {
            try {
                int superstep = value._1 - 1;
                for (Map.Entry<K, List<Message>> entry : value._2.entrySet()) {
                    // List of messages may be empty in case of sending to self
                    Tuple3<Integer, K, List<Message>> message = new Tuple3<>(superstep + 1, readOnlyKey, entry.getValue());
                    ProducerRecord<K, Tuple3<Integer, K, List<Message>>> producerRecord =
                        new ProducerRecord<>(workSetTopic, entry.getKey(), message);
                    producer.send(producerRecord, (metadata, error) -> {
                        if (error == null) {
                            try {
                                // Activate partition for next step
                                int p = vertexToPartition(entry.getKey(), serialized.keySerde().serializer(), numPartitions);
                                log.debug("adding partition {} for vertex {}", p, entry.getKey());
                                ZKUtils.addChild(curator, applicationId, new PregelState(State.RUNNING, superstep + 1, Stage.SEND), "partition-" + p);

                                Map<Integer, Long> endOffsets = lastWrittenOffsets.computeIfAbsent(superstep, k -> new ConcurrentHashMap<>());
                                endOffsets.put(metadata.partition(), metadata.offset());
                            } catch (Exception e) {
                                throw toRuntimeException(e);
                            }
                        }
                    });
                }
                producer.flush();
                // Deactivate this vertex
                deactivateVertex(superstep, readOnlyKey);
            } catch (Exception e) {
                throw toRuntimeException(e);
            }
        }

        private void deactivateVertex(int superstep, K vertex) throws Exception {
            int partition = vertexToPartition(vertex, serialized.keySerde().serializer(), numPartitions);
            Map<Integer, Set<K>> active = activeVertices.get(superstep);
            Set<K> vertices = active.get(partition);
            vertices.remove(vertex);
            log.debug("vertex {} for partition {} for step {} is NOT active", vertex, partition, superstep);
            if (vertices.isEmpty()) {
                // Deactivate partition
                log.debug("removing partition {} for last vertex {}", partition, vertex);
                ZKUtils.removeChild(curator, applicationId, new PregelState(State.RUNNING, superstep, Stage.SEND), "partition-" + partition);
                ComputeFunction.Aggregators aggregators = new ComputeFunction.Aggregators(
                    previousAggregates(superstep), aggregators(partition, superstep));
                computeFunction.postSuperstep(superstep, aggregators);
                aggregators.aggregate(LAST_WRITTEN_OFFSETS, lastWrittenOffsets.get(superstep));
                writeAggregate(superstep, partition);
            }
        }

        private void writeAggregate(int superstep, int partition) throws Exception {
            Map<Integer, Map<String, Aggregator<?>>> stepAggregators = aggregators.get(superstep);
            if (stepAggregators != null) {
                Map<String, Aggregator<?>> partitionAggregators = stepAggregators.get(partition);
                if (partitionAggregators != null) {
                    ZKUtils.addChild(curator, ZKUtils.aggregatePath(applicationId, superstep), "partition-" + partition,
                        CreateMode.PERSISTENT, KryoUtils.serialize(partitionAggregators));
                }
            }
        }

        @Override
        public void close() {
            producer.close();
        }
    }

    @Override
    public void close() {
        try {
            // Clean up ZK
            ZKUtils.removeRoot(curator, applicationId);
        } catch (Exception e) {
            // ignore
        }
    }

    protected static class AggregatorWrapper<T> {
        private final Class<? extends Aggregator<T>> aggregatorClass;
        private final boolean persistent;

        public AggregatorWrapper(
            Class<? extends Aggregator<T>> aggregatorClass,
            boolean persistent
        ) {
            this.aggregatorClass = aggregatorClass;
            this.persistent = persistent;
        }

        public Class<? extends Aggregator<T>> getAggregatorClass() {
            return aggregatorClass;
        }

        public boolean isPersistent() {
            return persistent;
        }
    }

    public static class MapOfLongMaxAggregator implements Aggregator<Map<Integer, Long>> {

        private Map<Integer, Long> value = new HashMap<>();

        @Override
        public Map<Integer, Long> getAggregate() {
            return value;
        }

        @Override
        public void setAggregate(Map<Integer, Long> value) {
            this.value = value;
        }

        @Override
        public void aggregate(Map<Integer, Long> value) {
            if (value != null) {
                for (Map.Entry<Integer, Long> entry : value.entrySet()) {
                    this.value.merge(entry.getKey(), entry.getValue(), Math::max);
                }
            }
        }

        @Override
        public void reset() {
            value = new HashMap<>();
        }
    }

    private static <K> int vertexToPartition(K vertex, Serializer<K> serializer, int numPartitions) {
        // TODO make configurable, currently this is tied to DefaultStreamPartitioner
        byte[] keyBytes = serializer.serialize(null, vertex);
        int partition = Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
        return partition;
    }

    private static void setPregelState(SharedValue sharedValue, PregelState pregelState) throws Exception {
        sharedValue.setValue(pregelState.toBytes());
        log.info("Set new pregel state {}", pregelState);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<byte[], byte[]> internalConsumer(ProcessorContext context)
        throws NoSuchFieldException, IllegalAccessException {
        // Consumer is created in a different thread, so can't use ThreadLocal; use reflection instead
        Field taskField = ProcessorContextImpl.class.getDeclaredField("task");
        taskField.setAccessible(true);
        StreamTask streamTask = (StreamTask) taskField.get(context);
        Field consumerField = AbstractTask.class.getDeclaredField("consumer");
        consumerField.setAccessible(true);
        return (Consumer<byte[], byte[]>) consumerField.get(streamTask);
    }

    private static boolean isTopicSynced(Consumer<byte[], byte[]> consumer, String topic,
                                         int superstep, Map<Integer, Long> lastWrittenOffsets) {
        Set<TopicPartition> partitions = localPartitions(consumer, topic);
        Map<TopicPartition, Long> positions = positions(consumer, partitions);
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

        // Consumer end offsets may be stale; use last written offset if available
        if (lastWrittenOffsets != null) {
            for (Map.Entry<TopicPartition, Long> endOffset : endOffsets.entrySet()) {
                Long lastWrittenOffset = lastWrittenOffsets.get(endOffset.getKey().partition());
                if (lastWrittenOffset != null && lastWrittenOffset >= endOffset.getValue()) {
                    endOffset.setValue(lastWrittenOffset + 1);
                }
            }
        } else {
            log.info("Topic {} has endoffsets {}", topic, endOffsets);
        }

        boolean synced = endOffsets.equals(positions);
        if (synced) {
            log.debug("Synced topic {}, step {}, offsets {}", topic, superstep, positions);
        }
        return synced;
    }

    private static Set<TopicPartition> localPartitions(Consumer<byte[], byte[]> consumer, String topic) {
        Set<TopicPartition> result = new HashSet<>();
        Set<TopicPartition> assignment = consumer.assignment();
        for (TopicPartition tp : assignment) {
            if (tp.topic().equals(topic)) {
                result.add(tp);
            }
        }
        return result;
    }

    private static Map<TopicPartition, Long> positions(Consumer<byte[], byte[]> consumer, Set<TopicPartition> tps) {
        Map<TopicPartition, Long> positions = new HashMap<>();
        for (TopicPartition tp : tps) {
            positions.put(tp, consumer.position(tp));
        }
        return positions;
    }

    private static RuntimeException toRuntimeException(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
    }
}
