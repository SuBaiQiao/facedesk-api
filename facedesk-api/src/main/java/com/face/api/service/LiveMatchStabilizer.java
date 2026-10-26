package com.face.api.service;

import com.face.api.model.*;

import java.util.*;

/**
 * 只连续确认已经通过单帧阈值的结果；不把旧身份写到当前未知人脸上。
 */
public class LiveMatchStabilizer {
    private final List<Track> tracks = new ArrayList<>();

    // 用检测框重叠关联短期轨迹；只平滑确认状态，不生成新检测框或补造身份结果。
    public void update(List<VideoFaceResult> faces, long now) {
        tracks.removeIf(t -> now - t.seen > 900);
        Set<Track> used = new HashSet<>();
        for (VideoFaceResult face : faces) {
            Track best = null;
            double first = .3, second = 0;
            for (Track t : tracks)
                if (!used.contains(t)) {
                    double overlap = iou(t.box, face.box);
                    if (overlap > first) {
                        second = first;
                        first = overlap;
                        best = t;
                    } else second = Math.max(second, overlap);
                }
            // 关联不明确时新建轨迹，避免相邻人脸互相继承已确认的姓名。
            if (best == null || first - second < .12) {
                best = new Track();
                tracks.add(best);
            }
            used.add(best);
            best.box = face.box;
            best.seen = now;
            // 投票只接受当前帧已通过阈值的身份；未知帧记为空票，不沿用上一帧姓名。
            String key = face.match == null ? "" : face.match.getFaceRole() + ":" + face.match.getCode();
            best.votes.addLast(key);
            while (best.votes.size() > 5) best.votes.removeFirst();
            face.confirmed = false;
            if (!key.isEmpty()) {
                int count = 0;
                for (String vote : best.votes) if (key.equals(vote)) count++;
                if (count >= 3 || (key.equals(best.confirmed) && now - best.lastConfirmed <= 700)) {
                    face.confirmed = true;
                    best.confirmed = key;
                    best.lastConfirmed = now;
                    face.displayState = "CONFIRMED";
                } else face.displayState = "CONFIRMING";
            } else
                face.displayState = best.confirmed != null && now - best.lastConfirmed <= 700 ? "RECHECK" : "UNMATCHED";
        }
    }

    private static double iou(FaceBox a, FaceBox b) {
        if (a == null || b == null) return 0;
        double intersection = Math.max(0, Math.min(a.getRight(), b.getRight()) - Math.max(a.getLeft(), b.getLeft())) * (double) Math.max(0, Math.min(a.getBottom(), b.getBottom()) - Math.max(a.getTop(), b.getTop()));
        double total = (a.getRight() - a.getLeft()) * (double) (a.getBottom() - a.getTop()) + (b.getRight() - b.getLeft()) * (double) (b.getBottom() - b.getTop()) - intersection;
        return total <= 0 ? 0 : intersection / total;
    }

    private static class Track {
        FaceBox box;
        long seen, lastConfirmed;
        String confirmed;
        Deque<String> votes = new ArrayDeque<>();
    }
}
