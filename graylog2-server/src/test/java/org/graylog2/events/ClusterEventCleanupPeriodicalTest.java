/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.events;

import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.lordofthejars.nosqlunit.annotation.UsingDataSet;
import com.lordofthejars.nosqlunit.core.LoadStrategyEnum;
import com.lordofthejars.nosqlunit.mongodb.InMemoryMongoDb;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import org.graylog2.bindings.providers.MongoJackObjectMapperProvider;
import org.graylog2.database.MongoConnection;
import org.graylog2.database.MongoConnectionRule;
import org.graylog2.database.ObjectIdSerializer;
import org.graylog2.shared.jackson.SizeSerializer;
import org.graylog2.shared.rest.RangeJsonSerializer;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.lordofthejars.nosqlunit.mongodb.InMemoryMongoDb.InMemoryMongoRuleBuilder.newInMemoryMongoDbRule;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(MockitoJUnitRunner.class)
public class ClusterEventCleanupPeriodicalTest {
    @ClassRule
    public static final InMemoryMongoDb IN_MEMORY_MONGO_DB = newInMemoryMongoDbRule().build();
    private static final DateTime TIME = new DateTime(2015, 4, 1, 0, 0, DateTimeZone.UTC);

    @Rule
    public MongoConnectionRule mongoRule = MongoConnectionRule.build("test");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(new PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy())
            .registerModule(new JodaModule())
            .registerModule(new GuavaModule())
            .registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.SECONDS, false))
            .registerModule(new SimpleModule()
                    .addSerializer(new ObjectIdSerializer())
                    .addSerializer(new RangeJsonSerializer())
                    .addSerializer(new SizeSerializer()));
    private MongoConnection mongoConnection;
    private ClusterEventCleanupPeriodical clusterEventCleanupPeriodical;

    @Before
    public void setUpService() throws Exception {
        DateTimeUtils.setCurrentMillisFixed(TIME.getMillis());

        this.mongoConnection = mongoRule.getMongoConnection();

        MongoJackObjectMapperProvider provider = new MongoJackObjectMapperProvider(objectMapper);
        this.clusterEventCleanupPeriodical = new ClusterEventCleanupPeriodical(provider, mongoRule.getMongoConnection());
    }

    @After
    public void tearDown() {
        DateTimeUtils.setCurrentMillisSystem();
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.DELETE_ALL)
    public void testDoRun() throws Exception {
        final DBCollection collection = mongoConnection.getDatabase().getCollection(ClusterEventPeriodical.COLLECTION_NAME);
        assertThat(insertEvent(collection, 0L)).isTrue();
        assertThat(insertEvent(collection, TIME.getMillis())).isTrue();
        assertThat(insertEvent(collection, TIME.minus(ClusterEventCleanupPeriodical.DEFAULT_MAX_EVENT_AGE).getMillis())).isTrue();
        assertThat(insertEvent(collection, TIME.minus(2 * ClusterEventCleanupPeriodical.DEFAULT_MAX_EVENT_AGE).getMillis())).isTrue();
        assertThat(collection.count()).isEqualTo(4L);

        clusterEventCleanupPeriodical.run();

        assertThat(collection.count()).isEqualTo(2L);
    }

    private boolean insertEvent(DBCollection collection, long timestamp) {
        DBObject event = new BasicDBObjectBuilder()
                .add("timestamp", timestamp)
                .add("producer", "TEST-PRODUCER")
                .add("consumers", Collections.emptyList())
                .add("event_class", String.class.getCanonicalName())
                .add("payload", "Test" + timestamp)
                .get();
        return collection.save(event).getN() == 1;
    }
}