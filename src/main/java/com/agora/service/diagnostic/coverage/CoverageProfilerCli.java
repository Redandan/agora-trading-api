package com.agora.service.diagnostic.coverage;

import com.agora.service.diagnostic.coverage.CoverageProfiler.CoverageGapManifest;
import com.agora.service.diagnostic.coverage.CoverageProfiler.ProfileInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/** Local fixture CLI. It does not start Spring or open database/network connections. */
public final class CoverageProfilerCli {

    private CoverageProfilerCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        ObjectMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        ProfileInput input = mapper.readValue(parsed.input().toFile(), ProfileInput.class);
        CoverageGapManifest manifest = new CoverageProfiler().profile(input);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + System.lineSeparator();
        if (parsed.output() == null) {
            System.out.print(json);
        } else {
            Files.writeString(parsed.output(), json);
        }
    }

    private record Arguments(Path input, Path output) {
        private static Arguments parse(String[] args) {
            Path input = null;
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input" -> input = Path.of(requireValue(args, ++i, "--input"));
                    case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (input == null) {
                throw new IllegalArgumentException("Usage: CoverageProfilerCli --input <fixture.json> [--output <manifest.json>]");
            }
            return new Arguments(input, output);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
