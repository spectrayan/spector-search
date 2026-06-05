/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.bench.cognitive.model;

/**
 * A single relevance judgment mapping a query to a corpus memory with a graded score.
 *
 * <p>Maps directly to one line in {@code qrels.tsv}. The standard BEIR/TREC-format
 * TSV file uses columns: query_id, corpus_id, relevance_grade. Each judgment
 * indicates how relevant a specific corpus memory is to a specific query on a
 * 4-point scale.</p>
 *
 * <h3>Field Constraints</h3>
 * <ul>
 *   <li>{@code queryId} — must reference an existing query ID in {@code queries.jsonl}</li>
 *   <li>{@code corpusId} — must reference an existing corpus ID in {@code corpus.jsonl}</li>
 *   <li>{@code grade} — integer 0–3:
 *       0 = irrelevant, 1 = marginally relevant, 2 = relevant, 3 = highly relevant</li>
 * </ul>
 *
 * @param queryId   the query identifier this judgment applies to
 * @param corpusId  the corpus memory identifier being judged
 * @param grade     graded relevance score (0=irrelevant, 1=marginally relevant,
 *                  2=relevant, 3=highly relevant)
 */
public record RelevanceJudgment(
        String queryId,
        String corpusId,
        int grade
) {}
