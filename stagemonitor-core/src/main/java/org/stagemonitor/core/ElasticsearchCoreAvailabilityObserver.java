package org.stagemonitor.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stagemonitor.core.elasticsearch.AbstractElasticsearchFirstAvailabilityObserver;
import org.stagemonitor.core.elasticsearch.ElasticsearchClient;
import org.stagemonitor.core.metrics.metrics2.ElasticsearchReporter;
import org.stagemonitor.core.metrics.metrics2.Metric2Registry;
import org.stagemonitor.util.IOUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ElasticsearchCoreAvailabilityObserver extends AbstractElasticsearchFirstAvailabilityObserver {

	private static final Logger logger = LoggerFactory.getLogger(ElasticsearchCoreAvailabilityObserver.class);

	@Override
	protected void onElasticsearchFirstAvailable(ElasticsearchClient elasticsearchClient) {
		logger.debug("creating KibanaIndexAndMapping...");
		createKibanaIndexAndMappings(elasticsearchClient);
		sendConfigurationMapping(elasticsearchClient);
		logger.debug("created KibanaIndexAndMapping...");

		manageMetricsIndex(elasticsearchClient, corePlugin);

		reportToElasticsearch(corePlugin.getMetricRegistry(), corePlugin.getReportingIntervalElasticsearch(), corePlugin.getMeasurementSession());
	}

	private void createKibanaIndexAndMappings(ElasticsearchClient elasticsearchClient) {
		// makes sure the .kibana index is present and has the right mapping.
		// otherwise it leads to problems if stagemonitor sends the dashboards to the
		// .kibana index before it has been properly created by kibana
		elasticsearchClient.createIndexAndSendMapping(".kibana", "index-pattern", IOUtils.getResourceAsStream("kibana/kibana-index-index-pattern.json"));
		elasticsearchClient.createIndexAndSendMapping(".kibana", "search", IOUtils.getResourceAsStream("kibana/kibana-index-search.json"));
		elasticsearchClient.createIndexAndSendMapping(".kibana", "dashboard", IOUtils.getResourceAsStream("kibana/kibana-index-dashboard.json"));
		elasticsearchClient.createIndexAndSendMapping(".kibana", "visualization", IOUtils.getResourceAsStream("kibana/kibana-index-visualization.json"));
	}

	public static void sendConfigurationMapping(ElasticsearchClient elasticsearchClient) {
		final String mappingJson;
		try {
			mappingJson = IOUtils.toString(IOUtils.getResourceAsStream("stagemonitor-configuration-elasticsearch-mapping.json"));
			elasticsearchClient.sendMappingTemplate(mappingJson, "stagemonitor-configuration");
		} catch (IOException e) {
			logger.warn("Suppressed exception:", e);
		}
	}

	private static void manageMetricsIndex(ElasticsearchClient elasticsearchClient, CorePlugin corePlugin) {
		if (corePlugin.isReportToElasticsearch()) {
			elasticsearchClient.updateKibanaIndexPattern("kibana/stagemonitor-metrics-kibana-index-pattern.json",
					"/.kibana/index-pattern/stagemonitor-metrics-*");
			final String mappingJson = ElasticsearchClient.modifyIndexTemplate(
					corePlugin.getMetricsIndexTemplate(), corePlugin.getMoveToColdNodesAfterDays(), corePlugin.getNumberOfReplicas(), corePlugin.getNumberOfShards());
			elasticsearchClient.sendMappingTemplate(mappingJson, "stagemonitor-metrics");
			elasticsearchClient.createEmptyIndex(ElasticsearchReporter.getTodaysIndexName());
			elasticsearchClient.scheduleIndexManagement(ElasticsearchReporter.STAGEMONITOR_METRICS_INDEX_PREFIX,
					corePlugin.getMoveToColdNodesAfterDays(), corePlugin.getDeleteElasticsearchMetricsAfterDays());
		}
	}

	private void reportToElasticsearch(Metric2Registry metricRegistry, int reportingInterval,
									   final MeasurementSession measurementSession) {
		if (corePlugin.isReportToElasticsearch()) {
			logger.info("Sending metrics to Elasticsearch ({}) every {}s", corePlugin.getElasticsearchUrlsWithoutAuthenticationInformation(), reportingInterval);
		}
		if (corePlugin.isReportToElasticsearch() || corePlugin.isOnlyLogElasticsearchMetricReports()) {
			final ElasticsearchReporter reporter = ElasticsearchReporter.forRegistry(metricRegistry, corePlugin)
					.globalTags(measurementSession.asMap())
					.build();

			reporter.start(reportingInterval, TimeUnit.SECONDS);
			corePlugin.closeOnShutdown(reporter);
		} else {
			logger.info("Not sending metrics to Elasticsearch (url={}, interval={}s)", corePlugin.getElasticsearchUrlsWithoutAuthenticationInformation(), reportingInterval);
		}
	}

	@Override
	public int getPriority() {
		return Integer.MAX_VALUE;
	}
}
