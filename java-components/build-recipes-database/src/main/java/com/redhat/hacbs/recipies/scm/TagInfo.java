package com.redhat.hacbs.recipies.scm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TagInfo {

    private RepositoryInfo repoInfo;
    private String tag;
    private String hash;

    public TagInfo() {
    }

    public TagInfo(RepositoryInfo repoInfo, String tag, String hash) {
        this.repoInfo = repoInfo;
        this.tag = tag;
        this.hash = hash;
    }

    public RepositoryInfo getRepoInfo() {
        return repoInfo;
    }

    public void setRepoInfo(RepositoryInfo repoInfo) {
        this.repoInfo = repoInfo;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
