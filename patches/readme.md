### [JobRunnerStuck.patch](./JobRunnerStuck.patch)
Added improvements to prevent scheduled jobs from getting stuck.

**Job Runner**: Updates job status and locks in a new transaction. This ensures jobs are correctly marked as "Failed" instead of sticking in "Running" when database errors occur.

**Benefit**: Improves stability by ensuring jobs don't get stuck after a crash.