# Keep rules are unnecessary: the app has no reflection/serialization.
# Tink uses these compile-time annotations, which are not present at runtime.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
