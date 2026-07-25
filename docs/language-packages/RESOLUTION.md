# Component registry and resolution

**Status:** Draft implementation contract  
**Applies to:** language package format `0.1`

This document defines how installed package components are indexed, checked for compatibility, and combined into an inactive resolution plan.

A resolution plan describes what would be active. Creating a plan does not load a model, open a dictionary, or modify keyboard settings.

## Coordinates

Every installed component has an immutable coordinate:

```text
<package-id>@<package-version>:<component-id>@<component-version>
```

Example:

```text
org.example.german.models@2.1.0:ranker-small@1.4.2
```

Package versions and component versions are intentionally independent. A package may update metadata or profiles without changing a component payload.

Profiles use this coordinate:

```text
<package-id>@<package-version>:<profile-id>
```

User selections store exact component coordinates. This prevents package updates from silently replacing an explicit selection.

## Registry

The registry is built only from packages accepted by the immutable package store.

It indexes:

- all installed package versions;
- all local components;
- all profiles;
- component payload locations;
- internal and external dependencies.

Multiple versions of a package may be installed side by side.

Duplicate full component or profile coordinates are rejected while building the registry. They indicate a corrupted or externally modified store.

## Component references

An internal reference omits `packageId` and resolves only inside the exact owning package version.

```json
{
  "componentId": "dictionary"
}
```

An external reference includes `packageId` and may resolve across installed package versions.

```json
{
  "packageId": "org.example.german.models",
  "componentId": "ranker-small",
  "versionRange": ">=1.2.0 <2.0.0"
}
```

`versionRange` applies to the **component version**, not the containing package version.

When several external candidates match, resolution is deterministic:

1. highest matching component version;
2. highest containing package version;
3. highest component priority;
4. package ID and component ID lexical order.

The current parser supports:

- exact versions: `1.2.3` or `=1.2.3`;
- comparator sets: `>=1.2.0 <2.0.0`;
- caret ranges: `^1.2.3`;
- tilde ranges: `~1.2.3`;
- wildcard: `*`.

OR ranges using `||` and comma-separated ranges are deliberately rejected in format `0.1`.

## Compatibility evaluation

Every component is evaluated independently for a requested language, layout, and runtime environment.

A component is unavailable when any required condition fails:

- language does not overlap the target language tag;
- a declared layout list does not include the target layout;
- keyboard API is outside the declared range;
- Android ABI is unsupported;
- available RAM is below the declared minimum;
- the runtime does not support all declared tasks;
- a required capability is unsupported;
- a required runtime feature is unavailable;
- the installed payload is missing or its byte size changed;
- a required dependency is missing or incompatible;
- a dependency cycle exists.

Unknown optional capabilities do not make a component unavailable.

Optional missing or incompatible dependencies produce warnings only and are not automatically activated.

Compatibility checks are pure and do not instantiate component runtimes.

## Selection precedence

For each component slot, selection precedence is:

1. explicit user override;
2. selected profile;
3. automatic selection;
4. built-in keyboard fallback outside the package resolver.

The resolver never persists a result by itself.

## User overrides

A user override has one of three states:

### `Automatic`

Ignore the profile selection for this slot and use deterministic automatic selection.

### `Disabled`

Select no component for this slot. If another selected component requires a component from the disabled slot, the plan is invalid.

### `Select`

Select exact installed component coordinates using either:

- `replace` — replace profile components in this slot;
- `append` — retain profile components and add user components.

`append` is valid only for stackable slots.

## Stackable slots

These slots are stackable:

- `dictionary`;
- `language-rules`;
- `personalization-adapter`.

Installing a stackable component never activates it automatically. This prevents specialist dictionaries and experimental rule sets from silently changing typing behavior.

Stackable components are selected only by:

- a profile;
- an explicit user selection;
- a required dependency of another selected component.

The final order is ascending component `priority`, then stable component identity. Later runtime layers may use this order as the merge order.

## Exclusive slots

These slots are exclusive:

- `candidate-generator`;
- `context-ranker`;
- `correction-model`;
- `swipe-model`.

At most one component may be selected for each exclusive slot.

When no profile or user override selects an exclusive component, the resolver may select one automatically from compatible installed candidates.

Automatic ranking is deterministic:

1. exact language tag match;
2. base-language match;
3. exact layout match;
4. layout-independent component;
5. higher component priority;
6. higher component version;
7. higher package version;
8. lexical package and component IDs.

If more than one compatible candidate exists, the plan contains a warning describing the automatic choice. The UI may later require confirmation, but the underlying plan remains reproducible.

## Profiles

A profile is addressed by an exact installed package version and profile ID.

Profile references are resolved relative to the profile's owning package. Internal references cannot accidentally jump to another installed version of that package.

A missing required profile component makes the plan invalid. A missing optional profile component produces a warning.

Profiles are recommendations. They do not override explicit user choices and are not active merely because their package is installed.

## Dependencies

After initial slot selection, the resolver computes a closure of required dependencies.

A required dependency:

- is added automatically to an empty compatible slot;
- is appended to a stackable slot;
- is reused when the exact coordinate is already selected;
- creates an error when its slot is explicitly disabled;
- creates an error when a different component already occupies an exclusive slot.

Only required dependencies are added to the plan. Compatible optional dependencies remain discoverable through compatibility evaluation but are not selected automatically.

## Resolution plan

A plan contains:

- target language and layout;
- selected profile, if any;
- every slot and its activation mode;
- selected components and their source;
- all compatible alternatives;
- compatibility results for every installed component;
- warnings and errors.

Selection sources are:

- `User`;
- `Profile`;
- `Automatic`;
- `Dependency`.

A plan is valid when it contains no resolution errors. A valid plan may still contain warnings.

## Current boundaries

The current resolver does not yet:

- persist active profiles or user overrides;
- map built-in legacy dictionaries and models into registry components;
- probe GGUF metadata or instantiate runtimes;
- estimate real inference latency;
- choose profiles based on battery state or device performance;
- support model ensembles;
- activate any component.

The next integration layer will expose registry and plan inspection in the debug UI, followed by persisted selection state and runtime-specific component probes.
