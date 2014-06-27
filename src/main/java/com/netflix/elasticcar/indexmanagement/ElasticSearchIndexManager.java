package com.netflix.elasticcar.indexmanagement;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.elasticcar.configuration.IConfiguration;
import com.netflix.elasticcar.indexmanagement.exception.UnsupportedAutoIndexException;
import com.netflix.elasticcar.utils.ElasticsearchProcessMonitor;
import com.netflix.elasticcar.utils.SystemUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.status.IndexStatus;
import org.elasticsearch.action.admin.indices.status.IndicesStatusResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public class ElasticSearchIndexManager {

    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchIndexManager.class);
    private static final String SPACE_DELIMITER = " ";
    private static ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    static ScheduledFuture<?> scheduledFuture;
    private static final AtomicBoolean isMasterNode = new AtomicBoolean(false);

    @Inject
    protected ElasticSearchIndexManager(IConfiguration config) {
        Settings settings = ImmutableSettings.settingsBuilder().put("cluster.name", config.getAppName()).build();
        TransportClient localClient = new TransportClient(settings);
        localClient.addTransportAddress(new InetSocketTransportAddress(config.getHostIP(),config.getTransportTcpPort()));
        init(config,localClient);
    }

    private void init(IConfiguration config,TransportClient client) {
        scheduledFuture = executor.scheduleWithFixedDelay(new IndexAllocator(config,client), config.getAutoCreateIndexInitialStartDelaySeconds(), config.getAutoCreateIndexPeriodicScheduledDelaySeconds(), TimeUnit.SECONDS);
    }

    static class IndexAllocator implements Runnable {

        private final IConfiguration config;
        private final TransportClient client;

        IndexAllocator(IConfiguration config,TransportClient client) {
            this.config = config;
            this.client = client;
        }
        public void run() {
            try
            {
                logger.info("Running ElasticSearchIndexManager task ...");
                if(!config.isIndexAutoCreationEnabled()) {
                    logger.info("AutoCreation of Indices is disabled, hence stopping the current running thread.");
                    scheduledFuture.cancel(false);
                }
                if(!config.getASGName().toLowerCase().contains("master")) {
                    logger.info("Current node can never become a Master Node, hence stopping the current running thread.");
                    scheduledFuture.cancel(false);
                }
                // If Elasticsearch is started then only start the Index Manager
                if (!ElasticsearchProcessMonitor.isElasticsearchStarted()) {
                    String exceptionMsg = "Elasticsearch is not yet started, check back again later";
                    logger.info(exceptionMsg);
                    return;
                }
                if (!isMasterNode.get()) {
                    String URL =  "http://127.0.0.1:"+config.getHttpPort()+"/_cat/master";
                    String response = SystemUtils.runHttpGetCommand(URL);
                    //Split the response on Spaces to get IP
                    if(response == null || response.isEmpty()) {
                        logger.error("Response from URL : <"+URL+"> is Null or Empty, hence stopping the current running thread");
                        scheduledFuture.cancel(false);
                    }
                    //TODO Needs to be more robust
                    String ip = response.split(SPACE_DELIMITER)[2];
                    if (ip == null || ip.isEmpty()) {
                        logger.error("ip from URL : <"+URL+"> is Null or Empty, hence stopping the current running thread");
                        scheduledFuture.cancel(false);
                    }
                    if (ip.equalsIgnoreCase(config.getHostIP()) || ip.equalsIgnoreCase(config.getHostLocalIP()))
                        isMasterNode.set(true);
                    else {
                        //TODO Change to Debug after testing
                        logger.info("Current node is not a Master Node yet, hence sleeping for " + config.getAutoCreateIndexPeriodicScheduledDelaySeconds() + " Seconds");
                    }
                }

                if (isMasterNode.get()) {
                   logger.info("Current node is a Master Node. Now start Creating/Checking Indices.");
                }

                List<IndexMetadata> infoList;
                try {
                    infoList = buildInfo(config.getIndexMetadata());
                } catch (Exception e) {
                    //TODO Add Servo Monitoring so that it can be verified from dashboard
                    logger.error("Caught an exception while Building IndexMetadata information from Configuration Property");
                    return;
                }

                for (IndexMetadata indexMetadata : infoList) {
                    try {
                        if (client != null) {
                            checkIndexRetention(indexMetadata);
                            if (indexMetadata.isPreCreate()) {
                                preCreateIndex(indexMetadata);
                            }
                        }
                    } catch (Exception e) {
                        //TODO Add Servo Monitoring so that it can be verified from dashboard
                        logger.error("Caught an exception while Building IndexMetadata information from Configuration Property");
                        return;
                    }
                }
            }
            catch(Exception e)
            {
                logger.warn("Exception thrown while doing Index Maintenance", e);
            }
        }

        /**
         * Convert the JSON String of parameters to IndexMetadata objects
         * @param infoStr : JSON String with Parameters
         * @return list of IndexMetadata objects
         * @throws IOException
         */
        public static List<IndexMetadata> buildInfo(String infoStr) throws IOException {
            ObjectMapper jsonMapper = new DefaultObjectMapper();
            TypeReference<List<IndexMetadata>> typeRef = new TypeReference<List<IndexMetadata>>() {};
            return jsonMapper.readValue(infoStr, typeRef);
        }

        /**
         * Courtesy Jae Bae
         */
        public void checkIndexRetention(IndexMetadata indexMetadata) throws UnsupportedAutoIndexException {

            //Calculate the Past Retention date
            int pastRetentionCutoffDateDate = IndexUtils.getPastRetentionCutoffDate(indexMetadata);
            logger.info("Past Date = "+pastRetentionCutoffDateDate);
            //Find all the indices
            IndicesStatusResponse getIndicesResponse = client.admin().indices().prepareStatus().execute().actionGet(config.getAutoCreateIndexTimeout());
            Map<String, IndexStatus> indexStatusMap = getIndicesResponse.getIndices();
            if (!indexStatusMap.isEmpty()) {
                for (String indexName : indexStatusMap.keySet()) {
                    logger.info("Index Name = <"+indexName+">");
                    if (indexMetadata.getIndexNameFilter().filter(indexName) &&
                            indexMetadata.getIndexNameFilter().getNamePart(indexName).equalsIgnoreCase(indexMetadata.getIndexName())) {

                        //Extract date from Index Name
                        int indexDate = IndexUtils.getDateFromIndexName(indexMetadata, indexName);
                        logger.info("Date extracted from Index <"+indexName+"> = <"+indexDate+">");
                        //Delete old indices
                        if (indexDate <= pastRetentionCutoffDateDate) {
                            logger.info("Date extracted from index <"+indexDate+"> is past the retention date <"+pastRetentionCutoffDateDate+", hence deleting index now.");
                            deleteIndices(client, indexName, config.getAutoCreateIndexTimeout());
                        }
                    }
                }
            }//TODO : Remove after testing
            else{
                logger.info("Indexes Map is empty ... No Indices found");
            }
        }

        /**
         * Courtesy Jae Bae
         */
        public void deleteIndices(Client client, String indexName, int timeout) {
            logger.info("trying to delete " + indexName);
            DeleteIndexResponse deleteIndexResponse = client.admin().indices().prepareDelete(indexName).execute().actionGet(timeout);
            if (!deleteIndexResponse.isAcknowledged()) {
                throw new RuntimeException("INDEX DELETION FAILED");
            } else {
                logger.info(indexName + " deleted");
            }
        }

        /**
         * Courtesy Jae Bae
         */
        public void preCreateIndex(IndexMetadata indexMetadata) throws UnsupportedAutoIndexException {
            logger.info("trying to preCreate");
            IndicesStatusResponse getIndicesResponse = client.admin().indices().prepareStatus().execute().actionGet(config.getAutoCreateIndexTimeout());
            Map<String, IndexStatus> indexStatusMap = getIndicesResponse.getIndices();
            if (!indexStatusMap.isEmpty()) {
                for (String indexNameWithDateSuffix : indexStatusMap.keySet()) {
                    logger.info("Index Name = <"+indexNameWithDateSuffix+">");
                    if (indexMetadata.getIndexNameFilter().filter(indexNameWithDateSuffix) &&
                        indexMetadata.getIndexNameFilter().getNamePart(indexNameWithDateSuffix).equalsIgnoreCase(indexMetadata.getIndexName())) {

                        int futureRetentionDate = IndexUtils.getFutureRetentionDate(indexMetadata);
                        logger.info("Future Date = "+futureRetentionDate);
                        if (!client.admin().indices().prepareExists(indexMetadata.getIndexName() + futureRetentionDate).execute().actionGet(config.getAutoCreateIndexTimeout()).isExists()) {
                            client.admin().indices().prepareCreate(indexMetadata.getIndexName() + futureRetentionDate).execute().actionGet(config.getAutoCreateIndexTimeout());
                            logger.info(indexMetadata.getIndexName() + futureRetentionDate + " is created");
                        } else {
                            //TODO: Change to Debug after Testing
                            logger.warn(indexMetadata.getIndexName() + futureRetentionDate + "already exists");
                        }
                    }
                }
            }
        }
    }
}