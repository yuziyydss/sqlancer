# GaussDB EET stability investigation

## Symptom

Long-running EET soak campaigns against GaussDB intermittently fail with:

```text
ERROR: memory is temporarily unavailable
```

The error is returned by GaussDB while planning or executing randomized EET queries. It is not an EET result mismatch.

## Environment

- GaussDB Kernel 507.0.0 build 1268bd4d
- Host memory: about 30 GB total, about 19 GB available during the failure
- GaussDB process RSS: about 4.7 GB
- `max_process_memory`: 9 GB
- SQLancer EET oracle: 15 query modes before this investigation
- Typical failing query families:
  - correlated `EXISTS`
  - `NOT IN`
  - derived table
  - scalar subquery
  - `ANY` / `ALL`

## Evidence

The error appears after thousands to tens of thousands of unique randomized SQL statements. The same query often succeeds when replayed manually after the SQLancer process exits.

In a later short smoke run, the same error appeared after only 205 queries, while the process was executing a CTE-based EET query. This suggests the failure threshold is not fixed and may depend on cumulative server state rather than a single query shape.

A manually executed window-function EET query succeeded on the same server after the failing SQLancer process exited:

```sql
SELECT count(*)
FROM (
    SELECT row_number() OVER (
        PARTITION BY CASE WHEN FALSE THEN c0 ELSE c0 END
        ORDER BY c0
    ) AS value
    FROM t0
) AS q;
```

Example failing query from the GaussDB server log:

```sql
SELECT *
FROM t2 AS o
WHERE CASE
    WHEN (o.c0) NOT BETWEEN (o.c0) AND (o.c0)
    THEN EXISTS (SELECT 1 FROM t2 AS i WHERE ((i.c0) = (o.c0)))
    ELSE EXISTS (SELECT 1 FROM t2 AS i WHERE ((i.c0) = (o.c0)))
END;
```

This query succeeded when replayed manually after the failing SQLancer process had exited.

## Current hypothesis

The most likely cause is cumulative per-session or per-thread memory pressure inside GaussDB, potentially related to:

- SQL plan management
- cached plan metadata
- optimizer memory contexts
- per-thread memory accounting

This is not an operating-system OOM condition.

## Next steps

1. Capture a minimal query that reliably triggers the error on a fresh connection.
2. Compare memory usage before and after repeated executions of that query.
3. Test with a fresh connection after each failure to determine whether the state is session-scoped or server-scoped.
4. Query GaussDB memory-detail views and correlate them with the failing query.
5. Determine whether disabling specific optimizer or plan-cache features changes the failure threshold.

