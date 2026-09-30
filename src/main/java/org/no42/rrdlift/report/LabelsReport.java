/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.no42.rrdlift.labels.SnapshotInfo;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.opennms.TagScope;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.repo.RepositoryWalker;

/** Self-contained HTML report of the planned labels, for people. Machine-readable detail stays in plan.json. */
public final class LabelsReport {

    public static final int CAP = 1000;

    private static final String CSS = """
            :root{color-scheme:light dark;--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--line:#d2d2d7;--warn:#b25000;--head:#f5f5f7}
            @media (prefers-color-scheme:dark){:root{--bg:#1d1d1f;--fg:#f5f5f7;--muted:#a1a1a6;--line:#424245;--warn:#ff9f0a;--head:#2c2c2e}}
            body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
            main{max-width:1100px;margin:0 auto;padding:24px 16px}
            h1{font-size:22px}h2{font-size:17px;margin-top:32px}
            table{border-collapse:collapse;width:100%;margin:8px 0}
            th,td{border-bottom:1px solid var(--line);padding:4px 8px;text-align:left;vertical-align:top}
            th{background:var(--head);cursor:pointer}
            td.num,th.num{text-align:right}
            .warn{color:var(--warn);font-weight:600}.muted{color:var(--muted)}
            pre{margin:0;white-space:pre-wrap;font:12px ui-monospace,Menlo,monospace}
            dl{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px}dt{color:var(--muted)}
            input[type=search]{margin:4px 0;padding:4px 8px;width:260px}
            """;

    private static final String SCRIPT = """
            document.querySelectorAll('table[data-filter]').forEach(function(t){var i=document.createElement('input');
            i.type='search';i.placeholder='Filter';i.oninput=function(){var q=i.value.toLowerCase();
            Array.from(t.tBodies[0].rows).forEach(function(r){r.hidden=q!==''&&r.textContent.toLowerCase().indexOf(q)<0;});};
            t.parentNode.insertBefore(i,t);});
            document.querySelectorAll('th').forEach(function(th){th.onclick=function(){var t=th.closest('table'),
            b=t.tBodies[0],i=Array.prototype.indexOf.call(th.parentNode.children,th),asc=th.dataset.asc!=='1';
            th.dataset.asc=asc?'1':'0';Array.from(b.rows).sort(function(a,c){var x=a.cells[i].textContent,
            y=c.cells[i].textContent,nx=parseFloat(x),ny=parseFloat(y);var r=(!isNaN(nx)&&!isNaN(ny))?nx-ny:x.localeCompare(y);
            return asc?r:-r;}).forEach(function(r){b.appendChild(r);});};});
            """;

    private LabelsReport() {}

    public static void write(Plan plan, MetaTagConfig config, Boolean configMatched, Path out) throws IOException {
        Files.writeString(out, render(plan, config, configMatched), StandardCharsets.UTF_8);
    }

    public static String render(Plan plan, MetaTagConfig config, Boolean configMatched) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>rrdlift labels report</title><style>").append(CSS).append("</style></head><body><main>")
                .append("<h1>rrdlift labels report</h1>");
        header(h, plan, configMatched);
        tags(h, config);
        classes(h, plan);
        coverage(h, plan);
        attention(h, plan);
        pending(h, plan);
        h.append("</main><script>").append(SCRIPT).append("</script></body></html>\n");
        return h.toString();
    }

    private static void header(StringBuilder h, Plan plan, Boolean configMatched) {
        SnapshotInfo s = plan.snapshot();
        h.append("<section id=\"header\"><h2>Snapshot</h2><dl>");
        dt(h, "Plan created", iso(plan.createdAt()));
        dt(h, "Snapshot taken", s == null ? "unknown" : iso(s.snapshotSec()));
        dt(h, "Repository cutover", s == null ? "unknown" : iso(s.cutoverSec()));
        dt(h, "Backend", s == null || s.backendUrl() == null ? "unknown" : s.backendUrl());
        dt(h, "Meta-tag config hash", s == null || s.configHash() == null ? "not recorded" : s.configHash());
        dt(h, "Config matched at plan time", configMatched == null ? "not checked" : configMatched ? "yes" : "NO");
        h.append("</dl></section>");
    }

    private static void tags(StringBuilder h, MetaTagConfig config) {
        h.append("<section id=\"tags\"><h2>Configured tags</h2>");
        if (config == null) {
            h.append("<p class=\"warn\">No meta-tag configuration given (--opennms-home).</p></section>");
            return;
        }
        h.append("<table data-filter><thead><tr><th>Key</th><th>Expression</th><th>Scope</th></tr></thead><tbody>");
        config.scopes().forEach((key, scope) -> {
            String cls = scope == TagScope.UNCLASSIFIED ? " class=\"warn\"" : "";
            h.append("<tr><td>").append(esc(key)).append("</td><td><pre>").append(esc(config.tags().get(key)))
                    .append("</pre></td><td").append(cls).append('>').append(scope).append("</td></tr>");
        });
        if (config.exposeCategories()) {
            h.append("<tr><td>categories, cat_*</td><td><pre>node categories</pre></td><td>NODE</td></tr>");
        }
        h.append("</tbody></table></section>");
    }

    private static void classes(StringBuilder h, Plan plan) {
        Map<EntryClass, int[]> counts = new EnumMap<>(EntryClass.class); // [written, not written]
        for (PlanEntry e : plan.entries()) {
            counts.computeIfAbsent(e.entryClass(), k -> new int[2])[e.labels() != null ? 0 : 1]++;
        }
        h.append("<section id=\"classes\"><h2>Class summary</h2><table><thead><tr><th>Class</th>")
                .append("<th class=\"num\">Written</th><th class=\"num\">Not written</th></tr></thead><tbody>");
        counts.forEach((cls, c) -> h.append("<tr><td>").append(cls).append("</td><td class=\"num\">").append(c[0])
                .append("</td><td class=\"num\">").append(c[1]).append("</td></tr>"));
        h.append("</tbody></table></section>");
    }

    private static void coverage(StringBuilder h, Plan plan) {
        TreeSet<String> keys = new TreeSet<>();
        TreeSet<EntryClass> classes = new TreeSet<>();
        Map<String, Map<EntryClass, Integer>> counts = new TreeMap<>();
        Map<EntryClass, Integer> totals = new EnumMap<>(EntryClass.class);
        for (PlanEntry e : plan.entries()) {
            if (e.labels() == null) {
                continue;
            }
            classes.add(e.entryClass());
            totals.merge(e.entryClass(), 1, Integer::sum);
            for (String k : e.labels().keySet()) {
                keys.add(k);
                counts.computeIfAbsent(k, x -> new EnumMap<>(EntryClass.class)).merge(e.entryClass(), 1, Integer::sum);
            }
        }
        h.append("<section id=\"coverage\"><h2>Label coverage</h2><p class=\"muted\">Written series carrying each label, per class.</p>")
                .append("<table data-filter><thead><tr><th>Label</th>");
        for (EntryClass c : classes) {
            h.append("<th class=\"num\">").append(c).append(" (").append(totals.getOrDefault(c, 0)).append(")</th>");
        }
        h.append("</tr></thead><tbody>");
        for (String k : keys) {
            h.append("<tr><td>").append(esc(k)).append("</td>");
            for (EntryClass c : classes) {
                h.append("<td class=\"num\">").append(counts.get(k).getOrDefault(c, 0)).append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</tbody></table></section>");
    }

    private static void attention(StringBuilder h, Plan plan) {
        Map<String, TreeSet<String>> unresolvedByNode = new TreeMap<>();
        Map<String, Integer> countByNode = new TreeMap<>();
        List<PlanEntry> ambiguous = new ArrayList<>();
        for (PlanEntry e : plan.entries()) {
            if (e.entryClass() == EntryClass.ORPHAN_PARTIAL && e.resourceId() != null) {
                String node = RepositoryWalker.nodePrefix(e.resourceId());
                countByNode.merge(node, 1, Integer::sum);
                TreeSet<String> u = unresolvedByNode.computeIfAbsent(node, k -> new TreeSet<>());
                if (e.note() != null && e.note().startsWith("unresolved: ")) {
                    String names = e.note().substring("unresolved: ".length()).replace(" (skipped by --skip-partial)", "");
                    for (String name : names.split(", ")) {
                        u.add(name);
                    }
                }
            } else if (e.entryClass() == EntryClass.AMBIGUOUS) {
                ambiguous.add(e);
            }
        }
        h.append("<section id=\"attention\"><h2>Needs attention</h2><h3>Orphans with unresolved tags</h3>")
                .append("<table data-filter><thead><tr><th>Node directory</th><th class=\"num\">Entries</th>")
                .append("<th>Unresolved tags</th></tr></thead><tbody>");
        int shown = 0;
        for (Map.Entry<String, Integer> n : countByNode.entrySet()) {
            if (shown++ == CAP) {
                break;
            }
            h.append("<tr><td>").append(esc(n.getKey())).append("</td><td class=\"num\">").append(n.getValue())
                    .append("</td><td>").append(esc(String.join(", ", unresolvedByNode.get(n.getKey())))).append("</td></tr>");
        }
        h.append("</tbody></table>");
        capNote(h, countByNode.size());
        h.append("<h3>Ambiguous label sets</h3><table data-filter><thead><tr><th>Resource</th><th>Data source</th>")
                .append("<th>Label sets</th></tr></thead><tbody>");
        for (int i = 0; i < Math.min(CAP, ambiguous.size()); i++) {
            PlanEntry e = ambiguous.get(i);
            h.append("<tr><td>").append(esc(e.resourceId())).append("</td><td>").append(esc(e.dsName())).append("</td><td>");
            if (e.candidates() != null) {
                for (Map<String, String> c : e.candidates()) {
                    h.append("<pre>").append(esc(new TreeMap<>(c).toString())).append("</pre>");
                }
            }
            h.append("</td></tr>");
        }
        h.append("</tbody></table>");
        capNote(h, ambiguous.size());
        h.append("</section>");
    }

    private static void pending(StringBuilder h, Plan plan) {
        List<PlanEntry> pending = plan.entries().stream().filter(e -> e.entryClass() == EntryClass.PENDING).toList();
        h.append("<section id=\"pending\"><h2>Pending</h2><table data-filter><thead><tr><th>Resource</th>")
                .append("<th>Data source</th><th>Reason and rerun time</th></tr></thead><tbody>");
        for (int i = 0; i < Math.min(CAP, pending.size()); i++) {
            PlanEntry e = pending.get(i);
            h.append("<tr><td>").append(esc(e.resourceId())).append("</td><td>").append(esc(e.dsName()))
                    .append("</td><td>").append(esc(e.note())).append("</td></tr>");
        }
        h.append("</tbody></table>");
        capNote(h, pending.size());
        h.append("</section>");
    }

    private static void capNote(StringBuilder h, int total) {
        if (total > CAP) {
            h.append("<p class=\"muted\">Showing ").append(CAP).append(" of ").append(total)
                    .append(". Full detail: not-migrated.txt, plan.json, rrdlift explain.</p>");
        }
    }

    private static void dt(StringBuilder h, String term, String value) {
        h.append("<dt>").append(esc(term)).append("</dt><dd>").append(esc(value)).append("</dd>");
    }

    private static String iso(long sec) {
        return Instant.ofEpochSecond(sec).toString();
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
