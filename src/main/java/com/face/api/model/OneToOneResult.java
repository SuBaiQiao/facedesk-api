package com.face.api.model;

/** 1:1 比对结果。 */
public class OneToOneResult {
    private boolean matched;
    private double score;
    private double threshold;

    public OneToOneResult() {
    }

    public OneToOneResult(boolean matched, double score, double threshold) {
        this.matched = matched;
        this.score = score;
        this.threshold = threshold;
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }
}
