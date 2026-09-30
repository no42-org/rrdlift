/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SelectorsTest {

    @Test
    void escapesRegexMetacharactersInResourcePrefix() {
        assertThat(Selectors.resourcePrefix("response/10.0.0.1"))
                .isEqualTo("{resourceId=~\"response/10\\\\.0\\\\.0\\\\.1/.+\"}");
        assertThat(Selectors.regexEscape("a(b)+c[d]{e}|f^g$h*i?j\\k")).isEqualTo(
                "a\\(b\\)\\+c\\[d\\]\\{e\\}\\|f\\^g\\$h\\*i\\?j\\\\k");
    }

    @Test
    void exactSelectorSortsAndQuotes() {
        assertThat(Selectors.exact(Map.of("b", "say \"hi\"", "__name__", "x")))
                .isEqualTo("{__name__=\"x\",b=\"say \\\"hi\\\"\"}");
    }
}
