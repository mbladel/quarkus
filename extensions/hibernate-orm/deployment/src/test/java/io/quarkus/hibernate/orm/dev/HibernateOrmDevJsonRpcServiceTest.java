package io.quarkus.hibernate.orm.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.devui.tests.DevUIJsonRPCTest;
import io.quarkus.hibernate.orm.TestTags;
import io.quarkus.test.QuarkusDevModeTest;

@Tag(TestTags.DEVMODE)
public class HibernateOrmDevJsonRpcServiceTest extends DevUIJsonRPCTest {
    @RegisterExtension
    final static QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot((jar) -> jar.addClasses(MyEntityWithSuccessfulDDLGeneration.class)
                    .addAsResource("application.properties")
                    .addAsResource("import-custom-table-name.sql"));

    public HibernateOrmDevJsonRpcServiceTest() {
        super("io.quarkus.quarkus-hibernate-orm");
    }

    @Test
    public void testGetInfo() throws Exception {
        JsonNode info = super.executeJsonRPCMethod("getInfo");

        assertThat(info).isNotNull();
        assertThat(info.has("persistenceUnits")).isTrue();

        JsonNode persistenceUnits = info.get("persistenceUnits");
        assertThat(persistenceUnits.isArray()).isTrue();
        assertThat(persistenceUnits.size()).isEqualTo(1);

        JsonNode unit = persistenceUnits.get(0);
        assertThat(unit.get("name")).isEqualTo("<default>");

        assertThat(unit.get("managedEntities").size()).isEqualTo(1);
        JsonNode myEntity = unit.get("managedEntities").get(0);
        assertThat(myEntity.get("name")).isEqualTo(MyEntityWithSuccessfulDDLGeneration.NAME);
        assertThat(myEntity.get("className")).isEqualTo(MyEntityWithSuccessfulDDLGeneration.class.getName());
        assertThat(myEntity.get("tableName")).isEqualTo(MyEntityWithSuccessfulDDLGeneration.TABLE_NAME);

        assertThat(unit.get("namedQueries").size()).isEqualTo(1);
        JsonNode namedQuery = unit.get("namedQueries").get(0);
        assertThat(namedQuery.get("name")).isEqualTo("MyEntity.findAll");
        assertThat(namedQuery.get("query")).isEqualTo("SELECT e FROM MyEntity e ORDER BY e.name");
    }

    @Test
    public void testGetNumberOfPersistenceUnits() throws Exception {
        JsonNode units = super.executeJsonRPCMethod("getNumberOfPersistenceUnits");

        assertThat(units).isNotNull();
        assertThat(units.isNumber()).isTrue();
        assertThat(units.intValue()).isEqualTo(1);
    }

    @Test
    public void testGetNumberOfEntityTypes() throws Exception {
        JsonNode types = super.executeJsonRPCMethod("getNumberOfEntityTypes");

        assertThat(types).isNotNull();
        assertThat(types.isNumber()).isTrue();
        assertThat(types.intValue()).isEqualTo(1);
    }

    @Test
    public void testGetNumberOfNamedQueries() throws Exception {
        JsonNode queries = super.executeJsonRPCMethod("getNumberOfNamedQueries");

        assertThat(queries).isNotNull();
        assertThat(queries.isNumber()).isTrue();
        assertThat(queries.intValue()).isEqualTo(2);
    }

    @Test
    public void testExecuteHQLOk() throws Exception {
        JsonNode dataSet = super.executeJsonRPCMethod("executeHQL", Map.of(
                "hql", "select e from MyEntity e where e.id = 1",
                "persistenceUnit", "<default>",
                "pageNumber", 1,
                "pageSize", 15));

        assertThat(dataSet).isNotNull();
        assertThat(dataSet.has("totalNumberOfElements")).isTrue();
        assertThat(dataSet.has("data")).isTrue();
        assertThat(dataSet.has("error")).isFalse();

        JsonNode elements = dataSet.get("totalNumberOfElements");
        assertThat(elements.isNumber()).isTrue();
        assertThat(elements.intValue()).isEqualTo(1);

        JsonNode data = dataSet.get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).get("id").intValue()).isEqualTo(1);
        assertThat(data.get(0).get("name").textValue()).isEqualTo("default sql load script entity");
    }

    @Test
    public void testExecuteHQLError() throws Exception {
        JsonNode dataSet = super.executeJsonRPCMethod("executeHQL", Map.of(
                "hql", "select e from MyEntity e where e.wrong_field = 'value'",
                "persistenceUnit", "<default>",
                "pageNumber", 1,
                "pageSize", 15));

        assertThat(dataSet).isNotNull();
        assertThat(dataSet.has("totalNumberOfElements")).isTrue();
        assertThat(dataSet.has("data")).isFalse();
        assertThat(dataSet.has("error")).isTrue();

        JsonNode elements = dataSet.get("totalNumberOfElements");
        assertThat(elements.isNumber()).isTrue();
        assertThat(elements.intValue()).isEqualTo(-1);

        JsonNode error = dataSet.get("error");
        assertThat(error.isTextual()).isTrue();
        assertThat(error.textValue()).isEqualTo("Could not interpret path expression 'wrong_field'");
    }
}
