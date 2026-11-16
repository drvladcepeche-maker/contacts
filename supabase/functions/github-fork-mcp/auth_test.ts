import { assertEquals } from "jsr:@std/assert@1";
import { isAllowedGithubForkUser } from "./auth.ts";

Deno.test("allows only the explicitly configured user ID", () => {
  assertEquals(isAllowedGithubForkUser("user-a", "user-a"), true);
  assertEquals(isAllowedGithubForkUser("user-b", "user-a"), false);
});

Deno.test("fails closed when the administrator ID is absent or blank", () => {
  assertEquals(isAllowedGithubForkUser("user-a", undefined), false);
  assertEquals(isAllowedGithubForkUser("user-a", null), false);
  assertEquals(isAllowedGithubForkUser("user-a", "   "), false);
});

Deno.test("fails closed for an empty caller ID", () => {
  assertEquals(isAllowedGithubForkUser("", "user-a"), false);
});
