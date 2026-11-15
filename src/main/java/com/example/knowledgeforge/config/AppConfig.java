package com.example.knowledgeforge.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Period;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ładuje config.properties z classpath, opcjonalnie nadpisywany przez
 * config-local.properties (gitignorowany) i przez zmienne środowiskowe.
 * Zastępuje mechanizm @Value / @ConfigurationProperties ze Springa.
 *
 * <p>Źródła zmiennych środowiskowych (w kolejności priorytetu):
 * <ol>
 *   <li>prawdziwe zmienne procesu ({@code System.getenv}),</li>
 *   <li>plik {@code .env} w katalogu uruchomienia (gitignorowany — tu trzymamy hasła lokalnie).</li>
 * </ol>
 *
 * <p>Wartości w properties mogą odwoływać się do zmiennych przez {@code ${NAZWA}} albo
 * {@code ${NAZWA:domyślna}} — tak config.properties w repozytorium nie zawiera żadnych sekretów,
 * tylko odwołania. Dodatkowo działa konwencja nadpisywania: "db.url" -> zmienna "DB_URL".
 */
public class AppConfig {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

    private final Properties props;
    private final Map<String, String> dotenv;

    private AppConfig(Properties props, Map<String, String> dotenv) {
        this.props = props;
        this.dotenv = dotenv;
    }

    public static AppConfig load() {
        Properties props = new Properties();
        loadInto(props, "/config.properties", true);
        loadInto(props, "/config-local.properties", false);
        return new AppConfig(props, loadDotenv(Path.of(".env")));
    }

    /** Buduje AppConfig z gotowych Properties, z pominięciem odczytu z classpath i pliku .env — używane przez testy. */
    public static AppConfig fromProperties(Properties props) {
        return new AppConfig(props, Map.of());
    }

    /** Prosty parser pliku .env: KLUCZ=wartość, komentarze '#', opcjonalne "export " i cudzysłowy. Brak pliku = pusta mapa. */
    static Map<String, String> loadDotenv(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(file)) return values;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring("export ".length()).strip();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file.toAbsolutePath(), e);
        }
        return values;
    }

    private String env(String name) {
        String fromProcess = System.getenv(name);
        if (fromProcess != null && !fromProcess.isBlank()) return fromProcess;
        String fromFile = dotenv.get(name);
        return fromFile != null && !fromFile.isBlank() ? fromFile : null;
    }

    /** Podmienia ${NAZWA} / ${NAZWA:domyślna} na wartość zmiennej; brak zmiennej i brak domyślnej -> pusty string. */
    private String resolvePlaceholders(String value) {
        if (value == null || !value.contains("${")) return value;
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String resolved = env(m.group(1));
            if (resolved == null) resolved = m.group(2) != null ? m.group(2) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Wywoływane raz przy starcie (Main) — dane dostępowe do bazy NIE mają wartości domyślnych
     * w kodzie ani w repozytorium, więc ich brak ma przerwać start jednoznacznym komunikatem.
     */
    public void requireDatabaseCredentials() {
        StringBuilder missing = new StringBuilder();
        if (dbUrl().isBlank()) missing.append(" DB_URL");
        if (dbUsername().isBlank()) missing.append(" DB_USERNAME");
        if (dbPassword().isBlank()) missing.append(" DB_PASSWORD");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable(s):" + missing
                    + " — set them in the environment or in the .env file (see .env.example)");
        }
    }

    private static void loadInto(Properties props, String resource, boolean required) {
        try (InputStream in = AppConfig.class.getResourceAsStream(resource)) {
            if (in == null) {
                if (required) throw new IllegalStateException("Missing required classpath resource: " + resource);
                return;
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + resource, e);
        }
    }

    private String get(String key, String defaultValue) {
        String envKey = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        String fromEnv = env(envKey);
        if (fromEnv != null) return fromEnv;
        return resolvePlaceholders(props.getProperty(key, defaultValue));
    }

    public int serverPort() {
        return Integer.parseInt(get("server.port", "8081"));
    }

    /** Bez wartości domyślnej w kodzie — pochodzi z DB_URL (zob. requireDatabaseCredentials). */
    public String dbUrl() {
        return get("db.url", "");
    }

    /** Bez wartości domyślnej w kodzie — pochodzi z DB_USERNAME. */
    public String dbUsername() {
        return get("db.username", "");
    }

    /** Bez wartości domyślnej w kodzie — pochodzi WYŁĄCZNIE z DB_PASSWORD (zmienna albo plik .env). */
    public String dbPassword() {
        return get("db.password", "");
    }

    public String corsAllowedOrigin() {
        return get("cors.allowed-origin", "http://localhost:3000");
    }

    public String defaultUsername() {
        return get("default-user.username", "default");
    }

    public String defaultUserEmail() {
        return get("default-user.email", "default@knowledge-forge.local");
    }

    /** Folder na dysku, gdzie leżą pliki notatek (.kfdoc) i wygenerowane PDF-y — baza trzyma tylko ścieżki. */
    public String notesStoragePath() {
        return get("notes.storage.path", "./data/notes");
    }

    /** Włącza szczegółowe logi (FINE) z pakietu aplikacji — domyślnie włączone, wyłącz w prod przez DEBUG_LOGGING=false. */
    public boolean debugLogging() {
        return Boolean.parseBoolean(get("debug.logging", "true"));
    }

    // ===============================
    // ZAŁĄCZNIKI — pliki na dysku, baza trzyma tylko metadane + względną ścieżkę
    // ===============================

    /** Folder na dysku, gdzie leżą fizyczne pliki załączników (zob. storage.AttachmentStorage). */
    public String attachmentsStoragePath() {
        return get("attachments.storage.path", "./data/attachments");
    }

    /** Maksymalny rozmiar pojedynczego załącznika w MB — egzekwowany zarówno przez Jetty, jak i strumieniowo w AttachmentStorage. */
    public long attachmentsMaxFileSizeMb() {
        return Long.parseLong(get("attachments.max-file-size-mb", "100"));
    }

    // ===============================
    // BACKUP BAZY POSTGRESQL (pg_dump -Fc) — bez backupu plików załączników
    // ===============================

    public boolean backupEnabled() {
        return Boolean.parseBoolean(get("backup.enabled", "true"));
    }

    /** Folder na dysku, gdzie lądują pliki .dump (i tymczasowe .dump.part w trakcie tworzenia). */
    public String backupDirectory() {
        return get("backup.directory", "./data/database-backups");
    }

    public int backupRetentionDays() {
        return Integer.parseInt(get("backup.retention-days", "14"));
    }

    /** Godzina (0-23) codziennego automatycznego backupu, w lokalnej strefie czasowej. */
    public int backupScheduleHour() {
        return Integer.parseInt(get("backup.schedule-hour", "2"));
    }

    /** Minuta (0-59) codziennego automatycznego backupu, w lokalnej strefie czasowej. */
    public int backupScheduleMinute() {
        return Integer.parseInt(get("backup.schedule-minute", "0"));
    }

    /** Ścieżka do binarki pg_dump — domyślnie zakłada, że jest na PATH. */
    public String backupPgDumpPath() {
        return get("backup.pg-dump-path", "pg_dump");
    }

    /** Ścieżka do binarki pg_restore — domyślnie zakłada, że jest na PATH. */
    public String backupPgRestorePath() {
        return get("backup.pg-restore-path", "pg_restore");
    }

    // ===============================
    // ADMINISTRACYJNY ENDPOINT RESTORE — domyślnie WYŁĄCZONY
    // ===============================

    /** Musi być jawnie ustawione na true (BACKUP_RESTORE_ENABLED), inaczej /api/admin/backups/* zwraca 404. */
    public boolean backupRestoreEnabled() {
        return Boolean.parseBoolean(get("backup.restore.enabled", "false"));
    }

    /**
     * Sekret wymagany w nagłówku X-Restore-Secret. CELOWO puste domyślnie — pusty sekret
     * całkowicie blokuje endpoint (zob. AdminBackupServlet), więc nigdy nie trzymamy tu
     * prawdziwej wartości w repozytorium. Produkcyjny sekret ma pochodzić WYŁĄCZNIE
     * ze zmiennej środowiskowej BACKUP_RESTORE_SECRET.
     */
    public String backupRestoreSecret() {
        return get("backup.restore.secret", "");
    }

    // ===============================
    // WERYFIKACJA AKTUALNOŚCI NOTATEK (zob. dokumentacja/ACTUALITY_VERIFICATION.txt)
    // ===============================

    public boolean actualityVerificationEnabled() {
        return Boolean.parseBoolean(get("actuality.verification.enabled", "true"));
    }

    /**
     * Okres ważności jako java.time.Period (kalendarzowy — lata/miesiące/dni, nie sztywna liczba
     * sekund), np. "P2Y" = 2 lata. Parsowane (i więc zwalidowane) przy KAŻDYM wywołaniu — celowo
     * wołane raz, jawnie, zaraz po starcie (zob. Main / ActualityVerificationScheduler), żeby
     * błędny format przerwał uruchomienie aplikacji jednoznacznym wyjątkiem, zamiast po cichu
     * wyłączyć mechanizm dopiero przy pierwszej próbie użycia.
     */
    public Period actualityVerificationPeriod() {
        String raw = get("actuality.verification.period", "P2Y");
        try {
            return Period.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalStateException(
                    "Invalid actuality.verification.period '" + raw + "' — expected an ISO-8601 java.time.Period "
                            + "expression, e.g. P2Y (2 years) or P6M (6 months)", e);
        }
    }

    /** Surowy, pięciopolowy wyraz cron (minuta godzina dzień-miesiąca miesiąc dzień-tygodnia) — parsowany
     *  i walidowany w ActualityVerificationScheduler (jedyne miejsce zależne od cron-utils). */
    public String actualityVerificationCron() {
        return get("actuality.verification.cron", "0 17 * * 5");
    }

    /** Strefa czasowa harmonogramu I samego okresu ważności — jawna, niezależna od strefy serwera/OS. */
    public ZoneId actualityVerificationZoneId() {
        String raw = get("actuality.verification.zone-id", "Europe/Warsaw");
        try {
            return ZoneId.of(raw);
        } catch (java.time.DateTimeException e) {
            throw new IllegalStateException(
                    "Invalid actuality.verification.zone-id '" + raw + "' — expected a valid IANA zone id, "
                            + "e.g. Europe/Warsaw", e);
        }
    }

    // ===============================
    // WIDOCZNOŚĆ ELEMENTÓW UI — przełączniki bez wpływu na samą logikę, tylko na to, czy dany
    // element jest w ogóle pokazany w interfejsie
    // ===============================

    /**
     * Widoczność przycisku "Wczytaj dane binarne" (panel ratunkowy .kfdoc/.kfbundle, zob.
     * dokumentacja/BACKUP_STRATEGY.txt, poziom 4) w menu bocznym. Domyślnie true (widoczny) —
     * ustaw na false, żeby ukryć przycisk bez wyłączania samego mechanizmu w kodzie. Odczytywane
     * przez frontend przez GET /api/app-config (zob. web.AppConfigServlet) jako "visibleBinaryLoader".
     */
    public boolean visibleBinaryLoader() {
        return Boolean.parseBoolean(get("ui.visible-binary-loader", "true"));
    }

    // ===============================
    // CODZIENNY ZBIORCZY BACKUP NOTATEK/INSTRUKCJI (.kfbundle) — zob. dokumentacja/BACKUP_STRATEGY.txt
    // ===============================

    public boolean noteBundleBackupEnabled() {
        return Boolean.parseBoolean(get("note-bundle-backup.enabled", "true"));
    }

    /**
     * Folder na dysku (ŚCIEŻKA SYSTEMOWA, nie URL) dla jedynego, stale nadpisywanego pliku
     * knowledge-forge-notes-latest.kfbundle (i tymczasowego .kfbundle.part w trakcie tworzenia).
     * Celowo INNY katalog niż {@link #notesStoragePath()} — walidowana zgodność w konstruktorze
     * NoteBundleBackupService (zob. tam), nie tutaj.
     */
    public String noteBundleBackupDirectory() {
        return get("note-bundle-backup.directory", "./data/note-bundle-backups");
    }

    /** Surowy, pięciopolowy wyraz cron — parsowany i walidowany w NoteBundleBackupScheduler (jedyne miejsce zależne od cron-utils). */
    public String noteBundleBackupCron() {
        return get("note-bundle-backup.cron", "30 17 * * *");
    }

    /** Strefa czasowa harmonogramu — jawna, niezależna od strefy serwera/OS. */
    public ZoneId noteBundleBackupZoneId() {
        String raw = get("note-bundle-backup.zone-id", "Europe/Warsaw");
        try {
            return ZoneId.of(raw);
        } catch (java.time.DateTimeException e) {
            throw new IllegalStateException(
                    "Invalid note-bundle-backup.zone-id '" + raw + "' — expected a valid IANA zone id, "
                            + "e.g. Europe/Warsaw", e);
        }
    }
}
