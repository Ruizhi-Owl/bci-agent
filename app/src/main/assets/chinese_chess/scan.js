export const ScanPhase = Object.freeze({
    COLUMN: 'column',
    ROW: 'row',
    TARGET: 'target',
    MENU: 'menu',
    CONFIRM: 'confirm',
    RESULT: 'result',
    PAUSED: 'paused'
});

const operationItems = Object.freeze([
    {kind: 'return_selection', label: '返回选择'},
    {kind: 'back_column', label: '返回选路'},
    {kind: 'back_row', label: '返回选行'},
    {kind: 'new_game', label: '新游戏'},
    {kind: 'undo', label: '悔棋'},
    {kind: 'sound', label: '声音'},
    {kind: 'exit', label: '退出象棋'}
]);

/** Put the game-operation entry after all board choices in either direction. */
export function boardScanCandidates(items, direction) {
    const menu = {kind: 'menu', label: '棋局操作'};
    return direction < 0 ? [menu, ...items] : [...items, menu];
}

/** Omit return actions that are invalid at the current selection depth. */
export function operationScanCandidates(sourcePhase, direction) {
    const enabled = operationItems.filter(item => {
        if (item.kind === 'back_column') {
            return sourcePhase === ScanPhase.ROW || sourcePhase === ScanPhase.TARGET;
        }
        if (item.kind === 'back_row') return sourcePhase === ScanPhase.TARGET;
        return true;
    });
    return direction < 0 ? [...enabled].reverse() : enabled;
}

/** Pure cursor state used by the timed EEG scanner and its unit tests. */
export class ScanCursor {
    constructor() {
        // Menus start left-to-right; gaze changes only the scan direction.
        this.direction = 1;
        this.phase = ScanPhase.PAUSED;
        this.candidates = [];
        this.index = -1;
    }

    setPhase(phase, candidates, preferredKind = null) {
        this.phase = phase;
        this.candidates = [...candidates];
        if (this.candidates.length === 0) {
            this.index = -1;
            return null;
        }

        const preferredIndex = preferredKind === null
            ? -1
            : this.candidates.findIndex(item => item.kind === preferredKind);
        this.index = phase === ScanPhase.CONFIRM ? 0 : preferredIndex >= 0
            ? preferredIndex
            : this.direction < 0 ? this.candidates.length - 1 : 0;
        return this.current();
    }

    setDirectionFromAction(action) {
        // 视线方向与棋盘上的视觉移动方向保持一致。
        if (action === 'look_left') this.direction = -1;
        if (action === 'look_right') this.direction = 1;
        if (this.phase === ScanPhase.CONFIRM && /^(look_left|look_right)$/.test(action)) {
            this.index = action === 'look_left' ? 0 : this.candidates.length - 1;
        }
        return this.direction;
    }

    advance() {
        if (this.candidates.length === 0) return null;
        if (this.phase === ScanPhase.CONFIRM) return this.current();
        this.index = (this.index + this.direction + this.candidates.length) % this.candidates.length;
        return this.current();
    }

    current() {
        return this.index >= 0 ? this.candidates[this.index] : null;
    }

    snapshot() {
        return {
            phase: this.phase,
            direction: this.direction,
            candidates: [...this.candidates],
            index: this.index
        };
    }

    restore(snapshot) {
        if (!snapshot) return null;
        this.phase = snapshot.phase;
        this.direction = snapshot.direction < 0 ? -1 : 1;
        this.candidates = [...snapshot.candidates];
        this.index = Math.max(-1, Math.min(snapshot.index, this.candidates.length - 1));
        return this.current();
    }

    pause() {
        this.phase = ScanPhase.PAUSED;
        this.candidates = [];
        this.index = -1;
    }
}
