package app.fuelog.service;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class ChargerCatalogImporterTests {
    @Test void rawAndEncodedKeysKeepTheirPlusSign() {
        assertEquals("a+b/==",ChargerCatalogImporter.decodedKey("a+b/=="));
        assertEquals("a+b/==",ChargerCatalogImporter.decodedKey("a%2Bb%2F%3D%3D"));
    }
    @Test void skipsUnknownSharedRestrictedAndMalformedOutputInsteadOfGuessing() {
        var json=new ObjectMapper();
        String input="{\"statId\":\"a\",\"chgerId\":\"1\",\"statNm\":\"공공 충전소\",\"busiId\":\"ME\",\"lat\":\"37\",\"lng\":\"127\",\"output\":\"50\",\"method\":\"단독\"}";
        assertNotNull(ChargerCatalogImporter.parse(json.readTree(input),Instant.now()));
        assertEquals("서울시 테스트로 1",ChargerCatalogImporter.parse(json.readTree(input.replace("\"method\"", "\"addr\":\"서울시 테스트로 1\",\"method\"")),Instant.now())[8]);
        for(String changed:new String[]{input.replace("단독","동시"),input.replace("\"50\"","\"unknown\""),input.replace("\"ME\"","\"ZZ\""),input.replace("\"37\"","\"NaN\"")})
            assertNull(ChargerCatalogImporter.parse(json.readTree(changed),Instant.now()));
    }
}
