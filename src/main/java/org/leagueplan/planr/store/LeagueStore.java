package org.leagueplan.planr.store;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.leagueplan.planr.model.League;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class LeagueStore {

    private static final Path DATA_DIR = Path.of(System.getProperty("user.home"), ".planr");
    private static final Path LEAGUE_FILE = DATA_DIR.resolve("league.json");
    private static final Path LEAGUE_FILE_TMP = DATA_DIR.resolve("league.json.tmp");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper mapper;

    public LeagueStore() {
        // Custom HH:mm serialization overrides jsr310's default HH:mm:ss for LocalTime.
        SimpleModule localTimeModule = new SimpleModule();
        localTimeModule.addSerializer(LocalTime.class, new StdSerializer<>(LocalTime.class) {
            @Override
            public void serialize(LocalTime value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                gen.writeString(value.format(TIME_FORMAT));
            }
        });
        localTimeModule.addDeserializer(LocalTime.class, new StdDeserializer<>(LocalTime.class) {
            @Override
            public LocalTime deserialize(JsonParser p, DeserializationContext ctxt)
                    throws IOException {
                return LocalTime.parse(p.getText(), TIME_FORMAT);
            }
        });

        mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .registerModule(new JavaTimeModule())
            .registerModule(localTimeModule); // registered last so it wins for LocalTime
    }

    public League load() throws IOException {
        if (!Files.exists(LEAGUE_FILE)) {
            League empty = League.empty();
            save(empty);
            return empty;
        }
        League league = mapper.readValue(LEAGUE_FILE.toFile(), League.class);
        if (league.version() == 1) {
            league = new League(2, league.divisions(), league.fields(), null);
            save(league);
        }
        if (league.version() == 2) {
            league = new League(3, league.divisions(), league.fields(), null);
            save(league);
        }
        return league;
    }

    public void save(League league) throws IOException {
        Files.createDirectories(DATA_DIR);
        mapper.writeValue(LEAGUE_FILE_TMP.toFile(), league);
        // Write to a temp file then rename so a crash mid-write never corrupts the only copy.
        Files.move(LEAGUE_FILE_TMP, LEAGUE_FILE,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }
}
