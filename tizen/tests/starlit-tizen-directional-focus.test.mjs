import test from "node:test";
import assert from "node:assert/strict";
import { pickDirectionalTarget } from "../js/ui/navigation/directionalFocusTarget.js";
function box(x, y, w = 110, h = 80) { return {left:x,top:y,width:w,height:h}; }
function layout(...positions) {
  const nodes = positions.map((_, index) => ({ id: index }));
  return { nodes, rects: new Map(nodes.map((node, index) => [node, box(...positions[index])])) };
}
test("directional navigation uses nearest row then aligns by secondary axis", () => {
  const { nodes, rects } = layout([0,0],[150,0],[300,0],[0,130],[150,130],[300,130]);
  assert.equal(pickDirectionalTarget(nodes,nodes[0],"right",rects),nodes[1]);
  assert.equal(pickDirectionalTarget(nodes,nodes[0],"down",rects),nodes[3]);
  assert.equal(pickDirectionalTarget(nodes,nodes[1],"down",rects),nodes[4]);
  assert.equal(pickDirectionalTarget(nodes,nodes[4],"left",rects),nodes[3]);
  assert.equal(pickDirectionalTarget(nodes,nodes[5],"right",rects),null);
});
test("aligned candidate wins over closer off-axis candidate within target row", () => {
  const { nodes, rects } = layout([0,0],[400,120],[0,145]);
  assert.equal(pickDirectionalTarget(nodes,nodes[0],"down",rects),nodes[2]);
});
test("invalid direction and absent candidates do not move focus", () => {
  const { nodes, rects } = layout([0,0],[110,0]);
  assert.equal(pickDirectionalTarget(nodes,nodes[0],"up",rects),null);
  assert.equal(pickDirectionalTarget(nodes,nodes[0],"noop",rects),null);
  assert.equal(pickDirectionalTarget([],nodes[0],"left",rects),null);
});
