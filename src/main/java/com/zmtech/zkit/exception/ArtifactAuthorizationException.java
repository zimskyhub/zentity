package com.zmtech.zkit.exception;

import com.zmtech.zkit.artifact.ArtifactExecutionInfo;
import java.util.Deque;

/** 当组件验证失败时抛出。 */
public class ArtifactAuthorizationException extends BaseArtifactException {
    transient private ArtifactExecutionInfo artifactInfo = null;

    public ArtifactAuthorizationException(String str) { super(str); }
    public ArtifactAuthorizationException(String str, ArtifactExecutionInfo curInfo, Deque<ArtifactExecutionInfo> curStack) {
        super(str, curStack);
        artifactInfo = curInfo;
    }

    public ArtifactExecutionInfo getArtifactInfo() { return artifactInfo; }
}
