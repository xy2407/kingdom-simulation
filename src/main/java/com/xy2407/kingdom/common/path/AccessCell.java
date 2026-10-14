package com.xy2407.kingdom.common.path;

/**
 * 建筑的道路接入点（P2）：接入路格坐标（地面方块）+ 建筑到它的局部代价（BFS 步数）。
 */
public record AccessCell(long roadPos, float localCost) {
}
