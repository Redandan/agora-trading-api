from __future__ import annotations

import asyncio
import unittest

from research_mcp.server import mcp


class ResearchMcpServerContractTest(unittest.TestCase):
    def test_exact_five_tool_schema_includes_candidate_submission(self) -> None:
        tools = asyncio.run(mcp.list_tools())
        by_name = {tool.name: tool for tool in tools}
        self.assertEqual(
            set(by_name),
            {
                "get_research_status",
                "request_research_heartbeat",
                "submit_research_candidate_bundle",
                "get_research_run",
                "get_research_briefing",
            },
        )

        candidate = by_name["submit_research_candidate_bundle"]
        self.assertEqual(candidate.inputSchema.get("required"), ["bundle"])
        self.assertEqual(
            candidate.inputSchema["properties"]["bundle"],
            {
                "additionalProperties": True,
                "title": "Bundle",
                "type": "object",
            },
        )
        self.assertFalse(candidate.annotations.readOnlyHint)
        self.assertFalse(candidate.annotations.destructiveHint)
        self.assertTrue(candidate.annotations.idempotentHint)


if __name__ == "__main__":
    unittest.main()
