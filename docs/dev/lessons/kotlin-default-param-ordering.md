# New default parameters go BEFORE a trailing-lambda parameter, not after

Kotlin's trailing-lambda syntax requires the lambda to be the last
parameter. If you add a new default-valued parameter **after** the
lambda, every trailing-lambda call site breaks — the compiler
interprets the lambda as the non-lambda parameter and complains with

```
Argument type mismatch: actual type is '(??? → ???)', but '<NewType>' was expected.
```

Default values don't help; positional matching happens first.

## Worked failure (UD-232 session)

`GraphApiService` gained a new `throttleBudget: ThrottleBudget = …`
constructor parameter, added at the END of the parameter list.
`OneDriveProvider:24` calls
`GraphApiService(config) { forceRefresh -> tokenManager.getValidToken(forceRefresh).accessToken }`
— a trailing-lambda call. The compiler tried to bind the lambda to
`throttleBudget` and failed.

The fix: move the new parameter to BEFORE `tokenProvider` (with
default), and the trailing-lambda call site stays untouched.

## How to apply

- When adding a new optional parameter to a class/function whose
  callers use trailing-lambda syntax, **always insert it BEFORE the
  lambda parameter**.
- Check callers first:
  ```bash
  grep -rE "ClassName\(" core/ | grep "{"
  ```
  to find trailing-lambda sites before you decide on the new
  parameter's position.
- If the lambda is the last param AND has no default, you can't add a
  default param after it at all — either reorder (which breaks source
  compat for anyone passing the new param positionally) or add an
  overload.
- Annotate the new param with a short inline comment noting the
  ordering constraint, so future readers don't "clean up" by moving
  it to the end.
