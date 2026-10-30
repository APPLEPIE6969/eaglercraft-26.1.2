package net.minecraft.client.renderer;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.FaceInfo.Extent;
import net.minecraft.client.renderer.FaceInfo.VertexInfo;
import net.minecraft.core.Direction;
import net.minecraft.util.Util;

public enum FaceInfo {
   DOWN(
      new VertexInfo(Extent.MIN_X, Extent.MIN_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MIN_X, Extent.MIN_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MAX_X, Extent.MIN_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MAX_X, Extent.MIN_Y, Extent.MAX_Z)
   ),
   UP(
      new VertexInfo(Extent.MIN_X, Extent.MAX_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MIN_X, Extent.MAX_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MAX_X, Extent.MAX_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MAX_X, Extent.MAX_Y, Extent.MIN_Z)
   ),
   NORTH(
      new VertexInfo(Extent.MAX_X, Extent.MAX_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MAX_X, Extent.MIN_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MIN_X, Extent.MIN_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MIN_X, Extent.MAX_Y, Extent.MIN_Z)
   ),
   SOUTH(
      new VertexInfo(Extent.MIN_X, Extent.MAX_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MIN_X, Extent.MIN_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MAX_X, Extent.MIN_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MAX_X, Extent.MAX_Y, Extent.MAX_Z)
   ),
   WEST(
      new VertexInfo(Extent.MIN_X, Extent.MAX_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MIN_X, Extent.MIN_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MIN_X, Extent.MIN_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MIN_X, Extent.MAX_Y, Extent.MAX_Z)
   ),
   EAST(
      new VertexInfo(Extent.MAX_X, Extent.MAX_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MAX_X, Extent.MIN_Y, Extent.MAX_Z),
      new VertexInfo(Extent.MAX_X, Extent.MIN_Y, Extent.MIN_Z),
      new VertexInfo(Extent.MAX_X, Extent.MAX_Y, Extent.MIN_Z)
   );

   private static final Map<Direction, FaceInfo> BY_FACING = (Map<Direction, FaceInfo>)Util.make(new EnumMap<>(Direction.class), map -> {
      map.put(Direction.DOWN, DOWN);
      map.put(Direction.UP, UP);
      map.put(Direction.NORTH, NORTH);
      map.put(Direction.SOUTH, SOUTH);
      map.put(Direction.WEST, WEST);
      map.put(Direction.EAST, EAST);
   });
   private final VertexInfo[] infos;

   public static FaceInfo fromFacing(final Direction direction) {
      return BY_FACING.get(direction);
   }

   private FaceInfo(final VertexInfo... infos) {
      this.infos = infos;
   }

   public VertexInfo getVertexInfo(final int index) {
      return this.infos[index];
   }
}
