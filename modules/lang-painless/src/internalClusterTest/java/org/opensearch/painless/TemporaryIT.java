/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.painless;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.query.Operator;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.indices.IndicesQueryCache;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.breaker.HierarchyCircuitBreakerService;
import org.opensearch.ingest.IngestTestPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptType;
import org.opensearch.search.SearchService;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.FieldSortBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.ParameterizedStaticSettingsOpenSearchIntegTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.singletonMap;
import static org.opensearch.index.query.QueryBuilders.boolQuery;
import static org.opensearch.index.query.QueryBuilders.matchAllQuery;
import static org.opensearch.index.query.QueryBuilders.queryStringQuery;
import static org.opensearch.index.query.QueryBuilders.termQuery;
import static org.opensearch.search.SearchService.CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING;
import static org.opensearch.search.aggregations.PipelineAggregatorBuilders.bucketSort;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;

public class TemporaryIT extends OpenSearchIntegTestCase {
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put(SearchService.CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING.getKey(), true)
            .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(PainlessPlugin.class);
    }

    private void createTestIndex() throws IOException {
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject()
            .field("dynamic", "false")
            .startObject("_meta")
            .field("schema_version", 5)
            .endObject()
            .startObject("properties")
            .startObject("anomaly_grade").field("type", "double").endObject()
            .startObject("anomaly_score").field("type", "double").endObject()
            .startObject("approx_anomaly_start_time").field("type", "date").field("format", "strict_date_time||epoch_millis").endObject()
            .startObject("confidence").field("type", "double").endObject()
            .startObject("data_end_time").field("type", "date").field("format", "strict_date_time||epoch_millis").endObject()
            .startObject("data_start_time").field("type", "date").field("format", "strict_date_time||epoch_millis").endObject()
            .startObject("detector_id").field("type", "keyword").endObject()
            .startObject("entity").field("type", "nested")
            .startObject("properties")
            .startObject("name").field("type", "keyword").endObject()
            .startObject("value").field("type", "keyword").endObject()
            .endObject()
            .endObject()
            .startObject("error").field("type", "text").endObject()
            .startObject("execution_end_time").field("type", "date").field("format", "strict_date_time||epoch_millis").endObject()
            .startObject("execution_start_time").field("type", "date").field("format", "strict_date_time||epoch_millis").endObject()
            .startObject("expected_values").field("type", "nested")
            .startObject("properties")
            .startObject("likelihood").field("type", "double").endObject()
            .startObject("value_list").field("type", "nested")
            .startObject("properties")
            .startObject("data").field("type", "double").endObject()
            .startObject("feature_id").field("type", "keyword").endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .startObject("feature_data").field("type", "nested")
            .startObject("properties")
            .startObject("data").field("type", "double").endObject()
            .startObject("feature_id").field("type", "keyword").endObject()
            .endObject()
            .endObject()
            .startObject("is_anomaly").field("type", "boolean").endObject()
            .startObject("model_id").field("type", "keyword").endObject()
            .startObject("past_values").field("type", "nested")
            .startObject("properties")
            .startObject("data").field("type", "double").endObject()
            .startObject("feature_id").field("type", "keyword").endObject()
            .endObject()
            .endObject()
            .startObject("relevant_attribution").field("type", "nested")
            .startObject("properties")
            .startObject("data").field("type", "double").endObject()
            .startObject("feature_id").field("type", "keyword").endObject()
            .endObject()
            .endObject()
            .startObject("schema_version").field("type", "integer").endObject()
            .startObject("task_id").field("type", "keyword").endObject()
            .startObject("threshold").field("type", "double").endObject()
            .startObject("user").field("type", "nested")
            .startObject("properties")
            .startObject("backend_roles").field("type", "text").startObject("fields").startObject("keyword").field("type", "keyword").endObject().endObject().endObject()
            .startObject("custom_attribute_names").field("type", "text").startObject("fields").startObject("keyword").field("type", "keyword").endObject().endObject().endObject()
            .startObject("name").field("type", "text").startObject("fields").startObject("keyword").field("type", "keyword").field("ignore_above", 256).endObject().endObject().endObject()
            .startObject("roles").field("type", "text").startObject("fields").startObject("keyword").field("type", "keyword").endObject().endObject().endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject();

        assertAcked(
            prepareCreate("test")
                .setMapping(xContentBuilder)
                .setSettings(
                    Settings.builder()
                        .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                )
        );
    }

    private void indexTestData() {
        client().prepareIndex("test").setId("gRbUF").setSource("{\"detector_id\":\"VqbXro0B0N8KJjAbG28Y\",\"schema_version\":0,\"data_start_time\":5,\"data_end_time\":5,\"feature_data\":[{\"feature_id\":\"WQgvo\",\"feature_name\":\"PVhgc\",\"data\":0.9212883816892278},{\"feature_id\":\"JulWB\",\"feature_name\":\"HgOGN\",\"data\":0.27831399526601086}],\"execution_start_time\":5,\"execution_end_time\":5,\"anomaly_score\":0.5,\"anomaly_grade\":0.8,\"confidence\":0.1705822118682151,\"entity\":[{\"name\":\"ip-field\",\"value\":\"1.2.3.4\"},{\"name\":\"keyword-field\",\"value\":\"field-1\"}],\"user\":{\"name\":\"PBJzgZpg\",\"backend_roles\":[\"giOWwAZcpU\"],\"roles\":[\"all_access\"],\"custom_attribute_names\":[\"attribute=test\"],\"user_requested_tenant\":null},\"approx_anomaly_start_time\":1708035355000,\"relevant_attribution\":[{\"feature_id\":\"piyfg\",\"data\":0.7797511350635153},{\"feature_id\":\"pFhPl\",\"data\":0.680814523323366}],\"past_values\":[{\"feature_id\":\"mECeN\",\"data\":0.8577224651498027},{\"feature_id\":\"SSHho\",\"data\":0.36525036781711573}],\"expected_values\":[{\"likelihood\":0.712699398152217,\"value_list\":[{\"feature_id\":\"wOPWI\",\"data\":0.09344528571943234},{\"feature_id\":\"HMZbM\",\"data\":0.8899196238445849}]}],\"threshold\":7.513042281539716}", MediaTypeRegistry.JSON).get();
        client().prepareIndex("test").setId("vWCJa").setSource("{\"detector_id\":\"VqbXro0B0N8KJjAbG28Y\",\"schema_version\":0,\"data_start_time\":5,\"data_end_time\":5,\"feature_data\":[{\"feature_id\":\"Lmcsm\",\"feature_name\":\"iDXfc\",\"data\":0.9674434291471465},{\"feature_id\":\"qSUQl\",\"feature_name\":\"qbEoF\",\"data\":0.6504223878706881}],\"execution_start_time\":5,\"execution_end_time\":5,\"anomaly_score\":0.5,\"anomaly_grade\":0.5,\"confidence\":0.06614591879270315,\"entity\":[{\"name\":\"ip-field\",\"value\":\"5.6.7.8\"},{\"name\":\"keyword-field\",\"value\":\"field-2\"}],\"user\":{\"name\":\"dJHBbnuu\",\"backend_roles\":[\"HXqCilWVMf\"],\"roles\":[\"all_access\"],\"custom_attribute_names\":[\"attribute=test\"],\"user_requested_tenant\":null},\"approx_anomaly_start_time\":1708035355000,\"relevant_attribution\":[{\"feature_id\":\"Ufhtc\",\"data\":0.08750171412108843},{\"feature_id\":\"uyJWb\",\"data\":0.9333680688095377}],\"past_values\":[{\"feature_id\":\"qskfI\",\"data\":0.970802420410941},{\"feature_id\":\"gYdme\",\"data\":0.847333030542884}],\"expected_values\":[{\"likelihood\":0.001994250912530804,\"value_list\":[{\"feature_id\":\"pnLad\",\"data\":0.1614332721050905},{\"feature_id\":\"BtBBh\",\"data\":0.5734485976838636}]}],\"threshold\":8.580216939299472}", MediaTypeRegistry.JSON).get();
        client().prepareIndex("test").setId("VnVkC").setSource("{\"detector_id\":\"VqbXro0B0N8KJjAbG28Y\",\"schema_version\":0,\"data_start_time\":5,\"data_end_time\":5,\"feature_data\":[{\"feature_id\":\"IqHwm\",\"feature_name\":\"LCnRh\",\"data\":0.8929177514663842},{\"feature_id\":\"IcaxA\",\"feature_name\":\"HLuxV\",\"data\":0.8975549333747292}],\"execution_start_time\":5,\"execution_end_time\":5,\"anomaly_score\":0.5,\"anomaly_grade\":0.2,\"confidence\":0.06244189871920458,\"entity\":[{\"name\":\"ip-field\",\"value\":\"5.6.7.8\"},{\"name\":\"keyword-field\",\"value\":\"field-2\"}],\"user\":{\"name\":\"IBhQUsrP\",\"backend_roles\":[\"AeewVXqCYO\"],\"roles\":[\"all_access\"],\"custom_attribute_names\":[\"attribute=test\"],\"user_requested_tenant\":null},\"approx_anomaly_start_time\":1708035355000,\"relevant_attribution\":[{\"feature_id\":\"EptJC\",\"data\":0.6875058309428451},{\"feature_id\":\"IKFpg\",\"data\":0.3419015294070341}],\"past_values\":[{\"feature_id\":\"KnVpN\",\"data\":0.7255993126008243},{\"feature_id\":\"NxgkL\",\"data\":0.6884725049479412}],\"expected_values\":[{\"likelihood\":0.7352436055910023,\"value_list\":[{\"feature_id\":\"Cvddb\",\"data\":0.7457298326060673},{\"feature_id\":\"QhtZU\",\"data\":0.7327525344956058}]}],\"threshold\":6.517648854225251}", MediaTypeRegistry.JSON).get();
        refresh("test");
    }

    public void test() throws Exception {
        createTestIndex();
        indexTestData();

        /**
         * curl "localhost:57523/.opendistro-anomaly-results/_search?pretty" -H 'Content-Type: application/json' -d'
         * quote> {
         *     "query": {
         *         "bool": {
         *             "filter": {
         *                 "term": {
         *                     "detector_id": "Ue39ro0BJngQavFLX2Q-"
         *                 }
         *             }
         *         }
         *     },
         *     "aggs": {
         *         "multi_buckets": {
         *             "composite": {
         *                 "sources": [{
         *                     "keyword-field": {
         *                         "terms": {
         *                             "script": {
         *                                 "source": "String value = null; if (params == null || params._source == null || params._source.entity == null) { return \"\"; } for (item in params._source.entity) { if (item[\"name\"] == \"keyword-field\") { value = item['value']; break; } } return value;",
         *                                 "lang": "painless"
         *                             }
         *                         }
         *                     }
         *                 }]
         *             },
         *             "aggregations": {
         *                 "max": {
         *                     "max": {
         *                         "field": "anomaly_grade"
         *                     }
         *                 },
         *                 "multi_buckets_sort": {
         *                     "bucket_sort": {
         *                         "sort": [{
         *                             "max": {
         *                                 "order": "desc"
         *                             }
         *                         }],
         *                         "size": 10
         *                     }
         *                 }
         *             }
         *         }
         *     }
         * }'
         */

        QueryBuilder query = boolQuery().filter(
            termQuery("detector_id", "VqbXro0B0N8KJjAbG28Y")
        );

        AggregationBuilder agg = AggregationBuilders.composite("multi_buckets",
            Collections.singletonList(new TermsValuesSourceBuilder("keyword-field")
                .script(new Script(ScriptType.INLINE, "painless", "String value = null; if (params == null || params._source == null || params._source.entity == null) { return \"\"; } for (item in params._source.entity) { if (item[\"name\"] == \"keyword-field\") { value = item['value']; break; } } return value;", Collections.emptyMap()))
            )
        ).subAggregation(
            AggregationBuilders.max("max").field("anomaly_grade")
        ).subAggregation(
            bucketSort("multi_buckets_sort", Collections.singletonList(new FieldSortBuilder("max").order(SortOrder.DESC)))
                .size(10)
        );


        System.out.println(query);
        System.out.println(agg);

        SearchResponse response = client().prepareSearch("test")
            .setQuery(query)
            .addAggregation(agg)
            .get();

        System.out.println(response);
    }
}
