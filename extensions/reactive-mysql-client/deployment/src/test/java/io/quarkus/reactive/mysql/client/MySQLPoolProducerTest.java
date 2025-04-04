package io.quarkus.reactive.mysql.client;

import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vertx.sqlclient.Pool;

public class MySQLPoolProducerTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(BeanUsingBareMySQLClient.class)
                    .addClasses(BeanUsingMutinyMySQLClient.class));

    @Inject
    BeanUsingBareMySQLClient beanUsingBare;

    @Inject
    BeanUsingMutinyMySQLClient beanUsingMutiny;

    @Test
    public void testVertxInjection() {
        beanUsingBare.verify()
                .thenCompose(v -> beanUsingMutiny.verify())
                .toCompletableFuture()
                .join();
    }

    @ApplicationScoped
    static class BeanUsingBareMySQLClient {

        @Inject
        Pool mysqlClient;

        public CompletionStage<?> verify() {
            return mysqlClient.query("SELECT 1").execute().toCompletionStage();
        }
    }

    @ApplicationScoped
    static class BeanUsingMutinyMySQLClient {

        @Inject
        io.vertx.mutiny.sqlclient.Pool mysqlClient;

        public CompletionStage<Void> verify() {
            return mysqlClient.query("SELECT 1").execute()
                    .onItem().ignore().andContinueWithNull()
                    .subscribeAsCompletionStage();
        }
    }
}
