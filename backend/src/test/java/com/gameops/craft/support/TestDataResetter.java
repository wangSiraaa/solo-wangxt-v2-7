package com.gameops.craft.support;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Test-only helper: wipes all tables and reapplies the H2 seed, so tests that
 * mutate recipes/inventories/orders start from identical data even though
 * Spring caches the application context (and the in-mem DB) between test classes.
 */
@Component
public class TestDataResetter {

    private final DataSource dataSource;

    public TestDataResetter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void reset() {
        var populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.addScript(new ClassPathResource("db/reset-h2.sql"));
        populator.addScript(new ClassPathResource("data-seed.sql"));
        populator.execute(dataSource);
    }
}
