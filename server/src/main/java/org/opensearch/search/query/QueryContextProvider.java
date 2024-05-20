/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.query;

import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;

public class QueryContextProvider {
    private final PriorityQueue<OrderedQueryContext> contextQueue = new PriorityQueue<>(new Comparator<OrderedQueryContext>() {
        @Override
        public int compare(OrderedQueryContext o1, OrderedQueryContext o2) {
            return o1.getPriority() - o2.getPriority();
        }
    });

    public void addQueryContext(QueryCollectorContext collectorContext, int priority) {
        contextQueue.offer(new OrderedQueryContext(collectorContext, priority));
    }

    public QueryCollectorContext getComposedContext() {
        return new QueryCollectorContext("provider") {
            @Override
            Collector create(Collector in) throws IOException {
                OrderedQueryContext octx = contextQueue.poll();
                Collector collector = null;
                while (octx != null) {
                    collector = octx.getContext().create(collector);
                    octx = contextQueue.poll();
                }
                return collector;
            }

            @Override
            CollectorManager<?, ReduceableSearchResult> createManager(CollectorManager<?, ReduceableSearchResult> in) throws IOException {
                OrderedQueryContext octx = contextQueue.poll();
                CollectorManager<?, ReduceableSearchResult> manager = null;
                while (octx != null) {
                    manager = octx.getContext().createManager(manager);
                    octx = contextQueue.poll();
                }
                return manager;
            }
        };
    }
}

class OrderedQueryContext {
    private final int priority;
    private final QueryCollectorContext context;

    public OrderedQueryContext(QueryCollectorContext context, int priority) {
        this.priority = priority;
        this.context = context;
    }

    public QueryCollectorContext getContext() {
        return this.context;
    }

    public int getPriority() {
        return priority;
    }
}
