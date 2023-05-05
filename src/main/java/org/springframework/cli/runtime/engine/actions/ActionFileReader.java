/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.springframework.cli.runtime.engine.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;

import org.springframework.cli.SpringCliException;
import org.springframework.cli.util.FileExtensionUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.FileCopyUtils;

public class ActionFileReader {

	public static Optional<ActionsFile> read(Path pathToFile) {
		boolean isYamlFile = false;
		String fileExtension = FileExtensionUtils.getExtension(pathToFile.toString());
		if (!fileExtension.isEmpty()) {
			if (fileExtension.equalsIgnoreCase("yaml") || fileExtension.equalsIgnoreCase(("yml"))) {
				isYamlFile = true;
			}
		}
		if (!isYamlFile) {
			return Optional.empty();
		}
		return Optional.of(read(new FileSystemResource(pathToFile)));
	}

	public static ActionsFile read(Resource resource) {
		try {
			String actionFileString = asString(resource);
			ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
					.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
					.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			return mapper.readValue(actionFileString, ActionsFile.class);
		} catch (JsonProcessingException ex) {
			improveErrorMessage(resource);
			throw new SpringCliException("Could not deserialize action file " + resource.getDescription(), ex);
		} catch (IOException ex) {
			throw new SpringCliException("Could not read resource " + resource.getDescription() + " as a String.", ex);
		}
	}

	private static void improveErrorMessage(Resource resource) {
		try {
			String actionFileString = asString(resource);
			BufferedReader reader = new BufferedReader(new StringReader(actionFileString));
			String line=null;
			while( (line=reader.readLine()) != null )
			{
				if (line.trim().contains("action") && !line.trim().contains("actions:")) {
					throw new SpringCliException("Could not deserialize action file " + resource.getDescription() +
							".  You may have forgotton to put a colon after the field 'action'");
				}
			}

		} catch (IOException ex) {
			throw new SpringCliException("Could not read resource " + resource.getDescription() + " as a String.", ex);
		}
	}

	private static String asString(Resource resource) throws IOException {
		Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
		return FileCopyUtils.copyToString(reader);
	}
}
