// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// JVM stub-safe Uri test double. android.net.Uri's constructor is
// package-private, so production/test packages cannot subclass it; this
// helper lives in the same package (android.net) to access it. Stub
// constructors are no-ops and GuardXposedProvider's CRUD never touches its
// Uri/ContentValues args (inert null/0), so every method body returns empty.
// Written in Java: Uri also declares a static writeToParcel overload that
// confuses kotlinc's override resolution ("overrides nothing"); Java overload
// resolution handles it fine.
package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

public class TestFakeUri extends Uri {
    @Override public boolean isHierarchical() { return false; }
    @Override public boolean isRelative() { return true; }
    @Override public String getScheme() { return null; }
    @Override public String getSchemeSpecificPart() { return null; }
    @Override public String getEncodedSchemeSpecificPart() { return null; }
    @Override public String getAuthority() { return null; }
    @Override public String getEncodedAuthority() { return null; }
    @Override public String getUserInfo() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getHost() { return null; }
    @Override public int getPort() { return -1; }
    @Override public String getPath() { return null; }
    @Override public String getEncodedPath() { return null; }
    @Override public String getQuery() { return null; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public List<String> getPathSegments() { return Collections.emptyList(); }
    @Override public String getLastPathSegment() { return null; }
    @Override public String toString() { return "fake:test"; }
    @Override public Builder buildUpon() { return null; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { }
}
