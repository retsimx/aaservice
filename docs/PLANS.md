# Plans

## Planning Conventions

- Work is tracked as GitHub issues in the `retsimx/aaservice` repository.
- Each issue represents a single atomic task with acceptance criteria.
- Implementation follows the gh-autopilot workflow:
  1. Create a worktree branch (`feat-{issue-name}-{number}`)
  2. Implement against the issue body spec
  3. Build and verify locally
  4. Push branch and open a draft PR
  5. Post a summary comment on the issue
- PRs use Conventional Commits: `feat:`, `fix:`, `docs:`, `chore:`
- Issue numbers are referenced in commit messages with `Closes #N`

## Work Plan Format

Execution plans live in `docs/plans/work/{NNN}-{name}.md`. Each plan has:
- `Status: Active | Completed | Draft`
- Task list with acceptance criteria
- Decision log
- Progress notes
