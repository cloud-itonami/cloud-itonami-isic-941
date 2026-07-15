# Contributing

Thank you for your interest in contributing to this project.

## Process

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add or update tests
5. Ensure all tests pass: `nbb -m membershipassocorg.test`
6. Submit a pull request

## Guidelines

- All code must be portable `.cljc` (runnable in Clojure, ClojureScript, nbb)
- Avoid breaking the three HARD governor checks
- Add tests for any new functionality
- Keep the scope clearly documented in blueprint.edn
- Do not modify the scope-exclusion block list without broad consensus

## Licensing

By contributing to this project, you agree that your contributions will be licensed under the GNU Affero General Public License v3.0.
