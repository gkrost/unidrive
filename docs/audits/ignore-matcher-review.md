# Ignore matcher review

## Query paths

`IgnoreMatcher` documents canonical, root-relative query paths, but previously removed leading slashes and collapsed all trailing slashes. An absolute path such as `/build` could therefore be interpreted as the different path `build`. The matcher now rejects malformed paths and preserves the empty/root behavior and one trailing slash used to identify a directory.

## Oracle process deadlines

`GitOracle` previously read a child process stream to EOF before calling `waitFor` with a timeout. A stuck Git process could block that read indefinitely and hang CI. The harness now drains output concurrently, enforces deadlines, and forcibly terminates timed-out children for its Git commands.
