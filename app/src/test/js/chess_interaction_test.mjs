import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { ScanCursor, ScanPhase } from '../../main/assets/chinese_chess/scan.js';

let timers = 0;
const context = vm.createContext({
  ScanCursor, ScanPhase, console,
  window: { innerHeight: 800, addEventListener() {}, clearInterval() {}, setInterval() { timers++; return timers; } },
  document: { documentElement: { style: { setProperty() {} } }, addEventListener() {} },
});
const source = fs.readFileSync(new URL('../../main/assets/chinese_chess/main.js', import.meta.url), 'utf8')
  .replace(/^import[\s\S]*?;\s*/gm, '');
vm.runInContext(source + '\nglobalThis.Controller = GameController;', context);
const game = Object.create(context.Controller.prototype);
Object.assign(game, {
  chess: { gameOver: false, currentPlayer: 'red' }, isAIThinking: false,
  scanCursor: new ScanCursor(), scanIntervalMs: 1500, scanTimer: null,
  showActionConfirmModal() {}, renderScanFocus() {},
});
let canceled = 0, executed = 0;
game.cancelPendingAction = () => canceled++;
game.confirmPendingAction = () => executed++;

game.startConfirmScan('exit');
assert.equal(game.scanCursor.current().kind, 'cancel');
assert.equal(timers, 0, 'confirmation must not start an automatic scan timer');
game.handleBciAction('bite');
assert.equal(canceled, 1);
assert.equal(executed, 0, 'a bite on entry cannot exit the game');
game.handleBciAction('look_right');
game.handleBciAction('bite');
assert.equal(executed, 1);

game.isAIThinking = true;
game.startConfirmScan('new_game');
game.handleBciAction('look_right');
game.handleBciAction('bite');
assert.equal(executed, 2, 'the confirmation layer owns input even during opponent thinking');

let intervalRestarts = 0;
game.restartScanTimer = () => intervalRestarts++;
game.setScanInterval(1500);
assert.equal(intervalRestarts, 0, 'unchanged settings must not restart scanning');
game.setScanInterval(1800);
assert.equal(intervalRestarts, 1);

let returned = '';
game.startColumnScan = () => returned = 'column';
game.startRowScan = () => returned = 'row';
game.startTouchConfirmScan = action => returned = action;
game.returnToBoardSelection = () => returned = 'board';
game.scanCursor.setPhase(ScanPhase.ROW, [{ kind: 'piece' }]);
game.requestBack();
assert.equal(returned, 'column');
game.scanCursor.setPhase(ScanPhase.TARGET, [{ kind: 'move' }]);
game.requestBack();
assert.equal(returned, 'row');
game.scanCursor.setPhase(ScanPhase.COLUMN, [{ kind: 'column' }]);
game.requestBack();
assert.equal(returned, 'exit');
console.log('Chess controller: default cancel, modal ownership, Back and timer stability passed');
