/** @type (condition: boolean, message?: string) => asserts condition */
export function assert(condition, message) {
    if (!condition) {
        throw new Error(message ?? "Assertion failed");
    }
}
