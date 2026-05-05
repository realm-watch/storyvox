# Consumer ProGuard rules for :core-data
# v1 ships unobfuscated (per CONTEXT.md "YAGNI v1: ProGuard"), but anything
# that depends on us inherits these. Keep Room-generated DAO impls and
# Hilt-generated workers reflective if a downstream module ever enables R8.
-keep class in.jphe.storyvox.data.db.entity.** { *; }
-keep class in.jphe.storyvox.data.db.dao.** { *; }
-keep class in.jphe.storyvox.data.work.** { *; }
