/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

/** ORPHAN is only read from plans written by rrdlift 0.1; new plans use the ORPHAN_* classes. */
public enum EntryClass {
    MATCHED, OPENNMS, ORPHAN, AMBIGUOUS, SKIPPED, FAILED_READ, MTYPE_MISMATCH,
    PENDING, ORPHAN_COMPLETE, ORPHAN_PARTIAL, ORPHAN_MINIMAL;

    public boolean isOrphan() {
        return this == ORPHAN || this == ORPHAN_COMPLETE || this == ORPHAN_PARTIAL || this == ORPHAN_MINIMAL;
    }
}
