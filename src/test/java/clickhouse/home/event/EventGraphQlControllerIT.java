package clickhouse.home.event;

import clickhouse.home.support.ClickHouseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventGraphQlControllerIT extends ClickHouseIntegrationTest {

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @Test
    void createsReadsUpdatesAndMutatesAnEventOverGraphQl() {
        String createMutation = """
                mutation {
                  createEvent(input: {eventType: "purchase", userId: "user-gql-1", properties: [{key: "sku", value: "abc-123"}]}) {
                    eventId eventType userId properties { key value }
                  }
                }
                """;

        String eventId = graphQlTester.document(createMutation).execute()
                .path("createEvent.eventId").entity(String.class).get();

        graphQlTester.document(createMutation).execute()
                .path("createEvent.properties[0].key").entity(String.class).isEqualTo("sku");

        String eventQuery = """
                query($id: ID!) {
                  event(eventId: $id) { eventId userId eventType }
                }
                """;
        graphQlTester.document(eventQuery).variable("id", eventId).execute()
                .path("event.userId").entity(String.class).isEqualTo("user-gql-1");

        String updateMutation = """
                mutation($id: ID!) {
                  updateEvent(eventId: $id, input: {eventType: "purchase", userId: "user-gql-1", properties: [{key: "refunded", value: "true"}]}) {
                    properties { key value }
                  }
                }
                """;
        graphQlTester.document(updateMutation).variable("id", eventId).execute()
                .path("updateEvent.properties[0].value").entity(String.class).isEqualTo("true");

        String mutateMutation = """
                mutation($id: ID!) {
                  mutateEvent(eventId: $id, input: {eventType: "purchase_corrected"}) { eventType }
                }
                """;
        graphQlTester.document(mutateMutation).variable("id", eventId).execute()
                .path("mutateEvent.eventType").entity(String.class).isEqualTo("purchase_corrected");
    }

    @Test
    void missingEventReturnsAGraphQlError() {
        String eventQuery = """
                query($id: ID!) {
                  event(eventId: $id) { eventId }
                }
                """;

        graphQlTester.document(eventQuery)
                .variable("id", "00000000-0000-0000-0000-000000000000")
                .execute()
                .errors().expect(error -> error.getMessage().contains("Event not found"));
    }

    @Test
    void batchCreateListAndStatsWork() {
        String batchMutation = """
                mutation {
                  createEvents(inputs: [
                    {eventType: "batch_gql", userId: "user-gql-2", properties: []},
                    {eventType: "batch_gql", userId: "user-gql-3", properties: []}
                  ]) { eventId eventType }
                }
                """;
        List<String> created = graphQlTester.document(batchMutation).execute()
                .path("createEvents[*].eventType").entityList(String.class).get();
        assertThat(created).containsExactly("batch_gql", "batch_gql");

        String listQuery = """
                query {
                  events(eventType: "batch_gql", limit: 10) { userId }
                }
                """;
        List<String> users = graphQlTester.document(listQuery).execute()
                .path("events[*].userId").entityList(String.class).get();
        assertThat(users).hasSizeGreaterThanOrEqualTo(2);

        String statsQuery = """
                query {
                  eventStats { counts { eventType count } }
                }
                """;
        graphQlTester.document(statsQuery).execute()
                .path("eventStats.counts").entityList(Map.class).get();
    }
}
