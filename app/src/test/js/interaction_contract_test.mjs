import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { ScanCursor as ChessCursor } from '../../main/assets/chinese_chess/scan.js';

const require = createRequire(import.meta.url);
const { ScanCursor: MahjongCursor } = require('../../main/assets/mahjong/js/scan.js');
const { DdzScanCursor } = require('../../main/assets/doudizhu/js/bci.js');
const implementations = [
  { name: 'chess', create: () => new ChessCursor(), set: (c, p, items) => c.setPhase(p, items, 'confirm') },
  { name: 'mahjong', create: () => new MahjongCursor(), set: (c, p, items) => c.setPhase(p, items, 1, 0) },
  { name: 'doudizhu', create: () => new DdzScanCursor(), set: (c, p, items) => c.setPhase(p, items, 'horizontal') },
];

for (const implementation of implementations) {
  const cursor = implementation.create();
  implementation.set(cursor, 'board', [{ kind: 'a' }, { kind: 'b' }, { kind: 'c' }]);
  assert.equal(cursor.index, 0, `${implementation.name}: common initial direction`);
  cursor.advance();
  cursor.setDirectionFromAction('look_left');
  assert.equal(cursor.index, 1, 'direction change must not also jump one item');
  const snapshot = cursor.snapshot();

  for (const phase of implementation.name === 'doudizhu' ? ['confirm', 'confirm_inline'] : ['confirm']) {
    implementation.set(cursor, phase, [{ kind: 'cancel' }, { kind: 'confirm' }]);
    assert.equal(cursor.current().kind, 'cancel', `${implementation.name}: defaults to cancel`);
    for (let tick = 0; tick < 100; tick++) cursor.advance();
    assert.equal(cursor.current().kind, 'cancel', 'time must not select a destructive action');
    cursor.setDirectionFromAction('look_right');
    assert.equal(cursor.current().kind, 'confirm');
    cursor.setDirectionFromAction('look_right');
    assert.equal(cursor.current().kind, 'confirm', 'repeated right must not toggle back');
    cursor.setDirectionFromAction('look_left');
    assert.equal(cursor.current().kind, 'cancel');
  }

  cursor.restore(snapshot);
  assert.equal(cursor.index, snapshot.index, 'cancel restores the previous focus');
  assert.equal(cursor.direction, snapshot.direction, 'cancel restores the previous direction');
  assert.equal(cursor.current().kind, 'b');
  implementation.set(cursor, 'waiting', []);
  assert.equal(cursor.current(), null);
  assert.equal(cursor.advance(), null);
  console.log(`${implementation.name}: scan and confirmation contract passed`);
}
