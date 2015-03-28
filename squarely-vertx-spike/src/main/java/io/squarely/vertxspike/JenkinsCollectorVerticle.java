package io.squarely.vertxspike;

import io.vertx.java.redis.RedisClient;
import org.joda.time.DateTime;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;

import java.util.List;

public class JenkinsCollectorVerticle extends CollectorVerticle {
  private static final int JOB_LIMIT = 10;
  private Logger logger;
  private EventBus eventBus;
  private HttpClient httpClient;
  private RedisClient redis;

  public void start() {
    logger = container.logger();
    eventBus = vertx.eventBus();
    redis = new RedisClient(eventBus, MainVerticle.REDIS_ADDRESS);
    httpClient = vertx.createHttpClient()
      .setHost("builds.apache.org")
      .setPort(443)
      .setSSL(true)
      .setTryUseCompression(true);
    // Get the following error without turning keep alive off.  Looks like a vertx bug
    // SEVERE: Exception in Java verticle
    // java.nio.channels.ClosedChannelException
    httpClient.setKeepAlive(false);

    final boolean[] isRunning = {true};

    collect(aVoid -> {
      isRunning[0] = false;
    });

    vertx.setPeriodic(3600000, aLong -> {
      if (isRunning[0]) {
        logger.info("Collection aborted as previous run still executing");
        return;
      }

      isRunning[0] = true;

      collect(aVoid -> {
        isRunning[0] = false;
      });
    });

    logger.info("SonarQubeCollectorVerticle started");
  }

  private void collect(Handler<Void> handler) {
    logger.info("Collection started");
    getJobs(JOB_LIMIT, projects -> {
      transformMetrics(projects, metrics -> {
        publishNewMetrics(metrics, aVoid -> {
          saveMetrics(metrics, aVoid2 -> {
            logger.info("Collection finished");
            handler.handle(null);
          });
        });
      });
    });
  }

  private void getJobs(int jobLimit, Handler<JsonArray> handler) {
    httpClient.getNow("/api/json?pretty=true", response -> {
      response.bodyHandler(body -> {
        logger.info("Received jobs " + body);
        JsonArray jobs = new JsonObject(body.toString()).getArray("jobs");

        if (jobs == null) {
          logger.error("Could not retrieve jobs");
          jobs = new JsonArray();
        }

        logger.info("Received " + jobs.size() + " jobs");
        logger.info("Jobs limit set to " + jobLimit);

        List jobList = jobs.toList();
        int jobCount = jobList.size();

        while (jobCount > jobLimit) {
          jobCount--;
          jobList.remove(jobCount);
        }

        jobs = new JsonArray(jobList);
        logger.info("There are " + jobs.size() + " jobs after limiting");

        handler.handle(jobs);
      });
    });
  }

  private void transformMetrics(JsonArray jobs, Handler<JsonArray> handler) {
    logger.info("Transforming metrics");
    long time = getCurrentTimeInMillis();

    JsonArray newPoints = new JsonArray();
    JsonObject newMetric = new JsonObject();
    newMetric.putString("name", "ci.jenkins.job_color");
    newMetric.putArray("points", newPoints);

    for (int jobIndex = 0, jobCount = jobs.size(); jobIndex < jobCount; jobIndex++) {
      JsonObject job = jobs.get(jobIndex);
      String jobName = job.getString("name");
      String jobColor = job.getString("color");

      newPoints.addObject(new JsonObject()
        .putNumber("time", time)
        .putString("jobName", jobName)
        .putString("value", jobColor));
    }

    JsonArray newMetrics = new JsonArray();
    newMetrics.addObject(newMetric);

    handler.handle(newMetrics);
  }

  private long getCurrentTimeInMillis() {
    return DateTime.now().getMillis();
  }

  private void publishNewMetrics(JsonArray metrics, Handler<Void> handler) {
    logger.info("Publishing metrics to event bus");
    logger.info("New metrics " + metrics);
    eventBus.publish("io.squarely.vertxspike.metrics", metrics);
    handler.handle(null);
  }

  private void saveMetrics(JsonArray metrics, Handler<Void> handler) {
    logger.info("Saving metrics to Redis");
    redis.set("metrics.ci.sonarqube.apache", metrics.toString(), (Handler<Message<JsonObject>>) reply -> {
      String status = reply.body().getString("status");

      if (!"ok".equals(status)) {
        logger.error("Unexpected Redis reply status of " + status);
      }

      handler.handle(null);
    });
  }
}