package com.intelliassist.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlugGeneratorTest {

    @ParameterizedTest
    @CsvSource({
        "Acme Corp,              acme-corp",
        "UPPERCASE,              uppercase",
        "with-hyphens,           with-hyphens",
        "Numbers 123,            numbers-123"
    })
    void slugifyProducesUrlSafeOutput(String input, String expected) {
        assertThat(SlugGenerator.slugify(input)).isEqualTo(expected);
    }

    @Test
    void punctuationAndSpacingCollapseToSingleHyphens() {
        assertThat(SlugGenerator.slugify("Multiple   Spaces")).isEqualTo("multiple-spaces");
        assertThat(SlugGenerator.slugify("Symbols!@#$%^&*()")).isEqualTo("symbols");
        assertThat(SlugGenerator.slugify("  Leading and trailing  "))
                .isEqualTo("leading-and-trailing");
    }

    @Test
    void accentsAreTransliteratedNotDropped() {
        // "Cafe" must keep its letters: dropping the accented character would
        // change the word rather than normalise it.
        assertThat(SlugGenerator.slugify("Café Münchén")).isEqualTo("cafe-munchen");
    }

    @Test
    void nameThatSlugifiesToNothingStillProducesAValidSlug() {
        // A CJK or punctuation-only name leaves an empty string, which the
        // database CHECK constraint rejects.
        assertThat(SlugGenerator.slugify("日本語")).isEqualTo("org");
        assertThat(SlugGenerator.slugify("!!!")).isEqualTo("org");
    }

    @Test
    void longNamesAreTruncatedWithoutTrailingHyphen() {
        String slug = SlugGenerator.slugify("a".repeat(200));

        assertThat(slug.length()).isLessThanOrEqualTo(SlugGenerator.maxLength());
        assertThat(slug).doesNotEndWith("-");
    }

    @Test
    void unusedSlugIsReturnedUnchanged() {
        assertThat(SlugGenerator.generate("Acme", slug -> false)).isEqualTo("acme");
    }

    @Test
    void collidingSlugGetsANumericSuffix() {
        // Two customers can legitimately both be called Acme; the second must
        // still be able to register.
        Set<String> taken = Set.of("acme");

        assertThat(SlugGenerator.generate("Acme", taken::contains)).isEqualTo("acme-2");
    }

    @Test
    void suffixIncrementsPastMultipleCollisions() {
        Set<String> taken = Set.of("acme", "acme-2", "acme-3");

        assertThat(SlugGenerator.generate("Acme", taken::contains)).isEqualTo("acme-4");
    }

    @Test
    void generatedSlugAlwaysMatchesTheDatabaseConstraint() {
        String pattern = "^[a-z0-9]+(-[a-z0-9]+)*$";

        for (String name : new String[] {"Acme", "  --weird--  ", "日本語", "A".repeat(200)}) {
            assertThat(SlugGenerator.slugify(name)).matches(pattern);
        }
    }

    @Test
    void givingUpIsLoudRatherThanInfinite() {
        assertThatThrownBy(() -> SlugGenerator.generate("Acme", slug -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique slug");
    }
}
