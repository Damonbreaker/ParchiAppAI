package com.kirana.parchi;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.kirana.common.Unit;
import com.kirana.parchi.ParchiLineParser.ParsedItem;

class ParchiLineParserTest {

    private final ParchiLineParser parser = new ParchiLineParser();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            aata 5kg                    | aata             | 5    | KG     | true
            चीनी 2 किलो                  | चीनी              | 2    | KG     | true
            doodh 2 ltr                 | doodh            | 2    | L      | true
            anda 1 darjan               | anda             | 1    | DOZEN  | true
            tata namak 1                | tata namak       | 1    | PCS    | true
            सरसों तेल 1 ली                | सरसों तेल          | 1    | L      | true
            maggi 4 pkt                 | maggi            | 4    | PACKET | true
            dhaniya 50gm                | dhaniya          | 50   | G      | true
            ½ kg pyaz                   | pyaz             | 0.5  | KG     | true
            bread                       | bread            | 1    | PCS    | false
            1. aata 5kg                 | aata             | 5    | KG     | true
            2) chawal 5 kilo            | chawal           | 5    | KG     | true
            - paneer 250gm              | paneer           | 250  | G      | true
            • jeera 50 gram             | jeera            | 50   | G      | true
            आलू २ किलो                   | आलू              | 2    | KG     | true
            मैदा २ किग्रा                  | मैदा              | 2    | KG     | true
            tamatar 1/2 kg              | tamatar          | 0.5  | KG     | true
            sooji 1½ kg                 | sooji            | 1.5  | KG     | true
            aata 1 1/2 kg               | aata             | 1.5  | KG     | true
            besan ¼kg                   | besan            | 0.25 | KG     | true
            दही 500 ग्राम                 | दही              | 500  | G      | true
            Fortune oil 1L              | Fortune oil      | 1    | L      | true
            amul taaza 500 ml           | amul taaza       | 500  | ML     | true
            kele 1 dozen                | kele             | 1    | DOZEN  | true
            nimbu 6 pcs                 | nimbu            | 6    | PCS    | true
            surf excel 1 packet         | surf excel       | 1    | PACKET | true
            5 kg aata                   | aata             | 5    | KG     | true
            arhar dal - 1 kg            | arhar dal        | 1    | KG     | true
            chai patti 0.25 kg          | chai patti       | 0.25 | KG     | true
            Haldi 100 GM                | Haldi            | 100  | G      | true
            parle-g biscuit 3 pkt       | parle-g biscuit  | 3    | PACKET | true
            हरी मिर्च 100 ग्राम            | हरी मिर्च          | 100  | G      | true
            dettol sabun 2              | dettol sabun     | 2    | PCS    | true
            aata (10 kg)                | aata             | 10   | KG     | true
            ashirvaad atta: 10 kg.      | ashirvaad atta   | 10   | KG     | true
            7up 2                       | 7up              | 2    | PCS    | true
            2 gaajar                    | gaajar           | 2    | PCS    | true
            अंडे १२ पीस                   | अंडे              | 12   | PCS    | true
            तेल 1 लीटर                   | तेल              | 1    | L      | true
            maggi 2 x 4 pkt             | maggi 2 x        | 4    | PACKET | false
            माचिस                        | माचिस             | 1    | PCS    | false
            agarbatti 0 pkt             | agarbatti        | 1    | PACKET | false
            """)
    void parsesRealLookingLines(String line, String name, String qty, Unit unit, boolean confident) {
        ParsedItem p = parser.parseLine(line).orElseThrow();
        assertThat(p.name()).isEqualTo(name);
        assertThat(p.quantity()).isEqualByComparingTo(new BigDecimal(qty));
        assertThat(p.unit()).isEqualTo(unit);
        assertThat(p.confident()).as("confident").isEqualTo(confident);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---", "~~~", "...", "।", "11/11/2026", "* * *", "12"})
    void dropsLinesWithNothingToOrder(String line) {
        assertThat(parser.parseLine(line)).isEmpty();
    }

    @Test
    void quantitiesArePlainNumbers() {
        ParsedItem p = parser.parseLine("doodh 2.000 ltr").orElseThrow();
        assertThat(p.quantity().toPlainString()).isEqualTo("2");
        assertThat(parser.parseLine("tamatar 1/3 kg").orElseThrow().quantity().toPlainString()).isEqualTo("0.333");
    }

    @Test
    void tooLongNamesAreCutAndFlagged() {
        String longName = "a".repeat(80);
        ParsedItem p = parser.parseLine(longName + " 1 kg").orElseThrow();
        assertThat(p.name()).hasSize(ParchiLineParser.MAX_NAME);
        assertThat(p.confident()).isFalse();
    }

    @Test
    void lineWithOnlyAQuantityIsKeptForReview() {
        ParsedItem p = parser.parseLine("5 kg").orElseThrow();
        assertThat(p.name()).isEqualTo("5 kg");
        assertThat(p.confident()).isFalse();
    }

    @Test
    void everyMockSampleParsesIntoItems() {
        for (List<String> sample : MockParchiReader.SAMPLES) {
            List<ParsedItem> items = parser.parse(sample);
            assertThat(items).isNotEmpty().allSatisfy(i -> {
                assertThat(i.name()).isNotBlank().hasSizeLessThanOrEqualTo(ParchiLineParser.MAX_NAME);
                assertThat(i.quantity()).isPositive();
            });
            // blank / punctuation lines in the samples are dropped
            assertThat(items.size()).isLessThanOrEqualTo(sample.size());
        }
        assertThat(parser.parse(MockParchiReader.SAMPLES.get(1))).hasSize(10); // "~~~" dropped
        assertThat(parser.parse(MockParchiReader.SAMPLES.get(2))).hasSize(11); // "" dropped
    }
}
