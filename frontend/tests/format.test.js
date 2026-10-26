import test from "node:test";
import assert from "node:assert/strict";
import { money, validAmount, isZero } from "../src/lib/format.js";
test("Oracle NUMBER(19,4) precision survives browser formatting", () => {
  assert.equal(money("123456789012345.1234"), "123,456,789,012,345.1234");
  assert.equal(money("1167.6600"), "1,167.66");
  assert.equal(money("0"), "0.00");
});
test("money input rejects unsafe and ambiguous representations", () => {
  for (const value of ["1e3", "Infinity", "-10", "10.001", "abc", ""])
    assert.notEqual(validAmount(value, "10", "100000"), "");
  assert.equal(money("<script>"), "Unavailable");
});
test("transaction limits include both boundaries exactly", () => {
  assert.equal(validAmount("10.00", "10", "100000"), "");
  assert.equal(validAmount("100000.00", "10", "100000"), "");
  assert.notEqual(validAmount("9.99", "10", "100000"), "");
  assert.notEqual(validAmount("100000.01", "10", "100000"), "");
});
test("zero ledger amounts are distinct from small nonzero entries", () => {
  assert.ok(isZero("0.0000"));
  assert.ok(!isZero("0.0001"));
});
