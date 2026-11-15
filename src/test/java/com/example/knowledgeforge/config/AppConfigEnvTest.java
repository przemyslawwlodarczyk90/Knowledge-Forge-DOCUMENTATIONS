package com.example.knowledgeforge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Odwołania ${...} w properties + parser pliku .env — sekrety nigdy nie leżą w repozytorium. */
class AppConfigEnvTest {

    @Test
    void placeholder_without_env_and_without_default_resolves_to_empty() {
        Properties props = new Properties();
        props.setProperty("db.password", "${KF_TEST_SURELY_UNDEFINED_VAR_123}");
        assertEquals("", AppConfig.fromProperties(props).dbPassword());
    }

    @Test
    void placeholder_falls_back_to_inline_default() {
        Properties props = new Properties();
        props.setProperty("backup.restore.enabled", "${KF_TEST_SURELY_UNDEFINED_VAR_123:false}");
        assertFalse(AppConfig.fromProperties(props).backupRestoreEnabled());
    }

    @Test
    void missing_database_credentials_abort_startup_with_clear_message() {
        AppConfig config = AppConfig.fromProperties(new Properties());
        IllegalStateException e = assertThrows(IllegalStateException.class, config::requireDatabaseCredentials);
        assertTrue(e.getMessage().contains("DB_PASSWORD"));
    }

    @Test
    void dotenv_parser_handles_comments_export_and_quotes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, """
                # komentarz
                DB_USERNAME=kf
                export DB_PASSWORD="s3cr=t"
                EMPTY=
                not a pair
                """);
        Map<String, String> values = AppConfig.loadDotenv(file);
        assertEquals("kf", values.get("DB_USERNAME"));
        assertEquals("s3cr=t", values.get("DB_PASSWORD"));
        assertEquals("", values.get("EMPTY"));
        assertEquals(3, values.size());
    }

    @Test
    void missing_dotenv_file_is_not_an_error(@TempDir Path dir) {
        assertTrue(AppConfig.loadDotenv(dir.resolve("nope.env")).isEmpty());
    }
}
