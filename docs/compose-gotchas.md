# Compose gotchas

Compose has scope-receiver tricks and stability rules that produce confusing
failures. This file collects the ones we've hit, so the next agent can grep
their compile error against this list before searching the internet.

Each entry follows the same shape:

- **Trap** — one-line description of the situation that triggers it.
- **Symptom** — the compile error, runtime behavior, or quiet performance
  failure you'll observe.
- **Fix** — concrete code change or import adjustment.
- **Receipt** — PR/commit where we hit and fixed it (optional, for the
  archaeology).

Entries are loosely ordered by failure-mode loudness:
loud (compile error) → quiet (runtime jank) → silent (only profilable).

---

## 1. Scope-receiver shadows the top-level overload

### `ColumnScope.AnimatedVisibility` overload picks itself silently

**Trap.** You're calling `AnimatedVisibility(...)` from inside a `Box` that
sits inside an outer `Column` (or `Row`). The `BoxScope` lambda receiver
inherits `ColumnScope` from the enclosing layout, so Compose's overload
resolution picks the `ColumnScope.AnimatedVisibility` extension instead of
the top-level `androidx.compose.animation.AnimatedVisibility(...)` overload.
The chosen overload doesn't match the actual receiver (BoxScope, not
ColumnScope), so the compiler complains — but the message points at the
call site, not at overload resolution, which makes it look like the body
is wrong.

**Symptom.**

```
e: AudiobookView.kt:143:21 'fun ColumnScope.AnimatedVisibility(...): Unit'
   cannot be called in this context with an implicit receiver. Use an
   explicit receiver if necessary.
e: AudiobookView.kt:148:25 @Composable invocations can only happen from
   the context of a @Composable function
```

The second error is misleading — the lambda **is** in a composable scope.
It's a downstream symptom of the first error breaking compilation of the
content lambda.

**Fix.** Fully qualify the call to bypass overload resolution:

```kotlin
androidx.compose.animation.AnimatedVisibility(
    visible = warmingUp,
    enter = enter,
    exit = exit,
) { /* content */ }
```

The plain `import androidx.compose.animation.AnimatedVisibility` doesn't
help because the scoped overload has higher priority once you're inside
ColumnScope/RowScope.

**Receipt.** PR #56 (spinner crossfade — both AudiobookView spinner sites).

### Same trap, broader family: `AnimatedContent` and `Crossfade`

The same scope-shadowing applies to:

- `ColumnScope.AnimatedContent` / `RowScope.AnimatedContent`
- `Crossfade` overloads with scope-receiver variants

If you hit "cannot be called in this context with an implicit receiver"
on any of these inside a Box-in-Column or Box-in-Row, the fix is the same
fully-qualified-package call. The pattern: a Compose function with both a
scope-extension and a top-level form will silently pick the scope one if
*any* enclosing scope makes it visible, even when your immediate
receiver doesn't match.

---

## 2. Extension function imports don't ride along with the receiver

### `animateFloat` and friends need their own import

**Trap.** You imported `rememberInfiniteTransition` and got an
`InfiniteTransition` instance. You call `transition.animateFloat(...)` and
the compiler can't find it — even though the receiver type is correct.

**Symptom.**

```
e: Unresolved reference 'animateFloat'
e: Cannot infer type for this parameter. Specify it explicitly.
```

The second error is misleading — it points inside the `tween(...)` block
and makes the spec lambda look wrong, but the actual problem is the
extension function isn't in scope, so the whole call's return type is
unknown, so type inference for the inner block can't proceed.

**Fix.** Import the extension explicitly:

```kotlin
import androidx.compose.animation.core.animateFloat
```

**Why it's misleading.** The `animateFloat` extension on
`InfiniteTransition` is declared in a separate file from
`rememberInfiniteTransition` and `infiniteRepeatable`. Importing the
constructor doesn't bring the extension along. The receiver type is
correct, so the symptom looks like a body problem rather than an import
problem.

**Pattern to anticipate.** Any Compose function with a paired
`Foo` / `Foo.bar` shape: `animateColor`, `animateValue`, `animateDp`,
`animateInt` are all extensions on the same receiver, all in their own
files. If `rememberInfiniteTransition` works but the `.animateX` chain
fails, suspect the extension import first.

**Receipt.** PR #59 (sleep timer countdown pulse).

---

## 3. `@Immutable` data class with mutable fields silently breaks stability

**Trap.** You annotated a data class `@Immutable` to give it Compose's
strong-skipping guarantee, then later added a runtime preference flag or
mutable state inside it. The compiler doesn't catch the contradiction, the
runtime doesn't crash, the snapshot system happily treats the class as
stable — and Compose now has a stability invariant that the data does not
honor.

**Symptom.** Phantom recompositions. A Composable that reads only the
"unchanging" fields of the class still recomposes when the mutable field
flips, because `@Immutable` told Compose it could skip equality checks but
the actual value differs. You profile with `LayoutInspector` or
`Recomposition counts` and see surfaces ticking when nothing visible
changed.

This is the most invisible failure mode in this file: no compile error,
no exception, no crash. Only a profiler shows it.

**Fix.** Don't put dynamic state inside `@Immutable`. If you have a token
class (timing curves, durations, color ramps) and a runtime preference
flag (reduced-motion, theme override), keep them in **separate**
`CompositionLocal`s:

```kotlin
@Immutable
data class Motion(
    val standardEasing: Easing = ...,
    val standardDurationMs: Int = 280,
    /* ... only timing tokens */
)

val LocalMotion = staticCompositionLocalOf { Motion() }
val LocalReducedMotion = staticCompositionLocalOf { false }
```

Reading at the call site:

```kotlin
val motion = LocalMotion.current
val reducedMotion = LocalReducedMotion.current
```

This keeps `Motion` honestly immutable (timing tokens never change at
runtime) and `LocalReducedMotion` honestly dynamic (system flag can flip).

**The general rule.** `@Immutable` is a **contract** with Compose's
stability system. If any field can vary at runtime, the class is not
immutable, even if the data class itself is `val`-based. When you find
yourself wanting to add a flag to an `@Immutable` token class, that's the
signal to make it a separate Local.

**Receipt.** Architectural decision around `LocalReducedMotion` (PR #51) —
not a fix for an observed phantom recomposition, but a forward-looking
choice to avoid the trap. The reasoning is preserved in PR #51's body and
in this entry.
