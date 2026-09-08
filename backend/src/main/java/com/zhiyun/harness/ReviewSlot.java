package com.zhiyun.harness;

import com.zhiyun.domain.DocumentVersion;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ReviewTask;

public class ReviewSlot {
    private ReviewTask task;
    private Manuscript manuscript;
    private DocumentVersion source;
    private long fencingToken;
    private String owner;

    public ReviewTask getTask() {
        return task;
    }

    public void setTask(ReviewTask task) {
        this.task = task;
    }

    public Manuscript getManuscript() {
        return manuscript;
    }

    public void setManuscript(Manuscript manuscript) {
        this.manuscript = manuscript;
    }

    public DocumentVersion getSource() {
        return source;
    }

    public void setSource(DocumentVersion source) {
        this.source = source;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }
}
