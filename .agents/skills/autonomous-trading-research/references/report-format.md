# Reporting format

Use the generated weekly report as evidence input, then explain it to the user
in this order:

1. one-sentence material conclusion;
2. matched-capital total PnL and maximum-drawdown change, when available;
3. realized/unrealized split and terminal inventory risk;
4. what hypothesis was rejected and what should not be repeated;
5. uncertainty, missing evidence, or sealed OOS status;
6. the next autonomous research question and its economic rationale;
7. one concept worth learning or questioning.

Do not report routine commands, permission mechanics, or every intermediate
artifact. Do not convert `NO_CANDIDATE` into a failure narrative. Clearly label
historical research, inference, and current runtime evidence.

For the monthly review, summarize the hypothesis tree, repeated stop reasons,
sealed-learning coverage, OOS use, and outstanding architecture evidence. Do
not add PnL from unlike parents or windows. The monthly review measures whether
the program is accumulating falsifiable knowledge, not whether it maximized run
count.

When the queue is intentionally waiting, name the active evidence trigger,
untouched start, minimum observation count, not-before review time, and excluded
closed branches. `WAITING_FOR_EVIDENCE` is not a performance conclusion.
`REVIEW_DUE` authorizes only the frozen read-only evidence review, not strategy
scoring or OOS consumption.
