/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.realtime.appenderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import org.apache.druid.client.CachingQueryRunner;
import org.apache.druid.client.cache.Cache;
import org.apache.druid.client.cache.CacheConfig;
import org.apache.druid.client.cache.CachePopulatorStats;
import org.apache.druid.client.cache.ForegroundCachePopulator;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.guava.FunctionalIterable;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.BySegmentQueryRunner;
import org.apache.druid.query.CPUTimeMetricQueryRunner;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.DirectQueryProcessingPool;
import org.apache.druid.query.FinalizeResultsQueryRunner;
import org.apache.druid.query.MetricsEmittingQueryRunner;
import org.apache.druid.query.NoopQueryRunner;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.QueryProcessingPool;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.QueryRunnerHelper;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.ReportTimelineMissingSegmentQueryRunner;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.SinkQueryRunners;
import org.apache.druid.query.planning.DataSourceAnalysis;
import org.apache.druid.query.spec.SpecificSegmentQueryRunner;
import org.apache.druid.query.spec.SpecificSegmentSpec;
import org.apache.druid.segment.SegmentReference;
import org.apache.druid.segment.StorageAdapter;
import org.apache.druid.segment.join.JoinableFactoryWrapper;
import org.apache.druid.segment.realtime.FireHydrant;
import org.apache.druid.segment.realtime.plumber.Sink;
import org.apache.druid.segment.realtime.plumber.SinkSegmentReference;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.utils.CloseableUtils;
import org.joda.time.Interval;

import java.io.Closeable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Query handler for indexing tasks.
 */
public class SinkQuerySegmentWalker implements QuerySegmentWalker
{
  private static final EmittingLogger log = new EmittingLogger(SinkQuerySegmentWalker.class);
  private static final String CONTEXT_SKIP_INCREMENTAL_SEGMENT = "skipIncrementalSegment";

  private final String dataSource;

  private final VersionedIntervalTimeline<String, Sink> sinkTimeline;
  private final ObjectMapper objectMapper;
  private final ServiceEmitter emitter;
  private final QueryRunnerFactoryConglomerate conglomerate;
  private final QueryProcessingPool queryProcessingPool;
  private final JoinableFactoryWrapper joinableFactoryWrapper;
  private final Cache cache;
  private final CacheConfig cacheConfig;
  private final CachePopulatorStats cachePopulatorStats;
  private final ConcurrentMap<SegmentDescriptor, SegmentDescriptor> newIdToBasePendingSegment
      = new ConcurrentHashMap<>();

  public SinkQuerySegmentWalker(
      String dataSource,
      VersionedIntervalTimeline<String, Sink> sinkTimeline,
      ObjectMapper objectMapper,
      ServiceEmitter emitter,
      QueryRunnerFactoryConglomerate conglomerate,
      QueryProcessingPool queryProcessingPool,
      JoinableFactoryWrapper joinableFactoryWrapper,
      Cache cache,
      CacheConfig cacheConfig,
      CachePopulatorStats cachePopulatorStats
  )
  {
    this.dataSource = Preconditions.checkNotNull(dataSource, "dataSource");
    this.sinkTimeline = Preconditions.checkNotNull(sinkTimeline, "sinkTimeline");
    this.objectMapper = Preconditions.checkNotNull(objectMapper, "objectMapper");
    this.emitter = Preconditions.checkNotNull(emitter, "emitter");
    this.conglomerate = Preconditions.checkNotNull(conglomerate, "conglomerate");
    this.queryProcessingPool = Preconditions.checkNotNull(queryProcessingPool, "queryProcessingPool");
    this.joinableFactoryWrapper = joinableFactoryWrapper;
    this.cache = Preconditions.checkNotNull(cache, "cache");
    this.cacheConfig = Preconditions.checkNotNull(cacheConfig, "cacheConfig");
    this.cachePopulatorStats = Preconditions.checkNotNull(cachePopulatorStats, "cachePopulatorStats");

    if (!cache.isLocal()) {
      log.warn("Configured cache[%s] is not local, caching will not be enabled.", cache.getClass().getName());
    }
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForIntervals(final Query<T> query, final Iterable<Interval> intervals)
  {
    final Iterable<SegmentDescriptor> specs = FunctionalIterable
        .create(intervals)
        .transformCat(sinkTimeline::lookup)
        .transformCat(
            holder -> FunctionalIterable
                .create(holder.getObject())
                .transform(
                    chunk -> new SegmentDescriptor(
                        holder.getInterval(),
                        holder.getVersion(),
                        chunk.getChunkNumber()
                    )
                )
        );

    return getQueryRunnerForSegments(query, specs);
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForSegments(final Query<T> query, final Iterable<SegmentDescriptor> specs)
  {
    // We only handle one particular dataSource. Make sure that's what we have, then ignore from here on out.
    final DataSource dataSourceFromQuery = query.getDataSource();
    final DataSourceAnalysis analysis = dataSourceFromQuery.getAnalysis();

    // Sanity check: make sure the query is based on the table we're meant to handle.
    if (!analysis.getBaseTableDataSource().filter(ds -> dataSource.equals(ds.getName())).isPresent()) {
      throw new ISE("Cannot handle datasource: %s", dataSourceFromQuery);
    }

    final QueryRunnerFactory<T, Query<T>> factory = conglomerate.findFactory(query);
    if (factory == null) {
      throw new ISE("Unknown query type[%s].", query.getClass());
    }

    final QueryToolChest<T, Query<T>> toolChest = factory.getToolchest();
    final boolean skipIncrementalSegment = query.context().getBoolean(CONTEXT_SKIP_INCREMENTAL_SEGMENT, false);
    final AtomicLong cpuTimeAccumulator = new AtomicLong(0L);

    // Make sure this query type can handle the subquery, if present.
    if ((dataSourceFromQuery instanceof QueryDataSource)
        && !toolChest.canPerformSubquery(((QueryDataSource) dataSourceFromQuery).getQuery())) {
      throw new ISE("Cannot handle subquery: %s", dataSourceFromQuery);
    }

    // segmentMapFn maps each base Segment into a joined Segment if necessary.
    final Function<SegmentReference, SegmentReference> segmentMapFn =
        dataSourceFromQuery.createSegmentMapFunction(
            query,
            cpuTimeAccumulator
        );

    // We compute the join cache key here itself so it doesn't need to be re-computed for every segment
    final Optional<byte[]> cacheKeyPrefix = Optional.ofNullable(query.getDataSource().getCacheKey());

    Iterable<QueryRunner<T>> perSegmentRunners = Iterables.transform(
        specs,
        newDescriptor -> {
          final SegmentDescriptor descriptor = newIdToBasePendingSegment.getOrDefault(newDescriptor, newDescriptor);
          final PartitionChunk<Sink> chunk = sinkTimeline.findChunk(
              descriptor.getInterval(),
              descriptor.getVersion(),
              descriptor.getPartitionNumber()
          );

          if (chunk == null) {
            return new ReportTimelineMissingSegmentQueryRunner<>(descriptor);
          }

          final Sink theSink = chunk.getObject();
          final SegmentId sinkSegmentId = theSink.getSegment().getId();
          final List<SinkSegmentReference> segmentReferences =
              theSink.acquireSegmentReferences(segmentMapFn, skipIncrementalSegment);

          if (segmentReferences == null) {
            // We failed to acquire references for all subsegments. Bail and report the entire sink missing.
            return new ReportTimelineMissingSegmentQueryRunner<>(descriptor);
          } else if (segmentReferences.isEmpty()) {
            return new NoopQueryRunner<>();
          }

          final Closeable releaser = () -> CloseableUtils.closeAll(segmentReferences);

          try {
            Iterable<QueryRunner<T>> perHydrantRunners = new SinkQueryRunners<>(
                Iterables.transform(
                    segmentReferences,
                    segmentReference -> {
                      QueryRunner<T> runner = factory.createRunner(segmentReference.getSegment());

                      // 1) Only use caching if data is immutable
                      // 2) Hydrants are not the same between replicas, make sure cache is local
                      if (segmentReference.isImmutable() && cache.isLocal()) {
                        StorageAdapter storageAdapter = segmentReference.getSegment().asStorageAdapter();
                        long segmentMinTime = storageAdapter.getMinTime().getMillis();
                        long segmentMaxTime = storageAdapter.getMaxTime().getMillis();
                        Interval actualDataInterval = Intervals.utc(segmentMinTime, segmentMaxTime + 1);
                        runner = new CachingQueryRunner<>(
                            makeHydrantCacheIdentifier(sinkSegmentId, segmentReference.getHydrantNumber()),
                            cacheKeyPrefix,
                            descriptor,
                            actualDataInterval,
                            objectMapper,
                            cache,
                            toolChest,
                            runner,
                            // Always populate in foreground regardless of config
                            new ForegroundCachePopulator(
                                objectMapper,
                                cachePopulatorStats,
                                cacheConfig.getMaxEntrySize()
                            ),
                            cacheConfig
                        );
                      }
                      return new Pair<>(segmentReference.getSegment().getDataInterval(), runner);
                    }
                )
            );
            return QueryRunnerHelper.makeClosingQueryRunner(
                new SpecificSegmentQueryRunner<>(
                    withPerSinkMetrics(
                        new BySegmentQueryRunner<>(
                            sinkSegmentId,
                            descriptor.getInterval().getStart(),
                            factory.mergeRunners(
                                DirectQueryProcessingPool.INSTANCE,
                                perHydrantRunners
                            )
                        ),
                        toolChest,
                        sinkSegmentId,
                        cpuTimeAccumulator
                    ),
                    new SpecificSegmentSpec(descriptor)
                ),
                releaser
            );
          }
          catch (Throwable e) {
            throw CloseableUtils.closeAndWrapInCatch(e, releaser);
          }
        }
    );
    final QueryRunner<T> mergedRunner =
        toolChest.mergeResults(
            factory.mergeRunners(
                queryProcessingPool,
                perSegmentRunners
            )
        );

    return CPUTimeMetricQueryRunner.safeBuild(
        new FinalizeResultsQueryRunner<>(mergedRunner, toolChest),
        toolChest,
        emitter,
        cpuTimeAccumulator,
        true
    );
  }

  public void registerNewVersionOfPendingSegment(
      SegmentIdWithShardSpec basePendingSegment,
      SegmentIdWithShardSpec newSegmentVersion
  )
  {
    newIdToBasePendingSegment.put(
        newSegmentVersion.asSegmentId().toDescriptor(),
        basePendingSegment.asSegmentId().toDescriptor()
    );
  }

  @VisibleForTesting
  String getDataSource()
  {
    return dataSource;
  }

  /**
   * Decorates a Sink's query runner to emit query/segmentAndCache/time, query/segment/time, query/wait/time once
   * each for the whole Sink. Also adds CPU time to cpuTimeAccumulator.
   */
  private <T> QueryRunner<T> withPerSinkMetrics(
      final QueryRunner<T> sinkRunner,
      final QueryToolChest<T, ? extends Query<T>> queryToolChest,
      final SegmentId sinkSegmentId,
      final AtomicLong cpuTimeAccumulator
  )
  {
    // Note: reportSegmentAndCacheTime and reportSegmentTime are effectively the same here. They don't split apart
    // cache vs. non-cache due to the fact that Sinks may be partially cached and partially uncached. Making this
    // better would need to involve another accumulator like the cpuTimeAccumulator that we could share with the
    // sinkRunner.
    String sinkSegmentIdString = sinkSegmentId.toString();
    return CPUTimeMetricQueryRunner.safeBuild(
        new MetricsEmittingQueryRunner<>(
            emitter,
            queryToolChest,
            new MetricsEmittingQueryRunner<>(
                emitter,
                queryToolChest,
                sinkRunner,
                QueryMetrics::reportSegmentTime,
                queryMetrics -> queryMetrics.segment(sinkSegmentIdString)
            ),
            QueryMetrics::reportSegmentAndCacheTime,
            queryMetrics -> queryMetrics.segment(sinkSegmentIdString)
        ).withWaitMeasuredFromNow(),
        queryToolChest,
        emitter,
        cpuTimeAccumulator,
        false
    );
  }

  public VersionedIntervalTimeline<String, Sink> getSinkTimeline()
  {
    return sinkTimeline;
  }

  public static String makeHydrantCacheIdentifier(final FireHydrant hydrant)
  {
    return makeHydrantCacheIdentifier(hydrant.getSegmentId(), hydrant.getCount());
  }

  public static String makeHydrantCacheIdentifier(final SegmentId segmentId, final int hydrantNumber)
  {
    // Cache ID like segmentId_H0, etc. The 'H' disambiguates subsegment [foo_x_y_z partition 0 hydrant 1]
    // from full segment [foo_x_y_z partition 1], and is therefore useful if we ever want the cache to mix full segments
    // with subsegments (hydrants).
    return segmentId + "_H" + hydrantNumber;
  }
}
