/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.context;

import java.util.List;
import java.util.Map;

/** A facade for AI/LLM operations.
 *
 * Provides a provider-agnostic interface for calling large language models.
 * Configured via the ai-facade block in MoquiConf.xml.
 * Access via ExecutionContext: ec.ai.getDefault().generate(messages)
 */
public interface AiFacade {

    /** Get a client for the 'default' model config, same as calling getConfig("default") */
    AiClient getDefault();

    /** Get a client for the named model config as declared in MoquiConf.xml ai-facade.model-config */
    AiClient getConfig(String name);

    interface AiClient {

        /** Send a list of chat messages to the LLM and return the generated text response.
         *
         * @param messages List of message Maps, each with "role" (e.g. "system", "user", "assistant")
         *                 and "content" (String) keys following the standard LLM chat format
         * @return The model's generated text response
         */
        String generate(List<Map> messages);

        /** Send a list of chat messages to the LLM and return a structured Map response
         * conforming to the given schema.
         *
         * The schema follows Moqui service parameter conventions — a nested Map describing
         * field names and their types. The schema is passed to the model API as a JSON Schema
         * parameter (not injected into the prompt) to enforce the exact output shape at the
         * API level. Example schema:
         * <pre>
         * [status: [type: "String"], amount: [type: "BigDecimal"],
         *  lineItems: [type: "List", parameters: [
         *      productId: [type: "String"],
         *      quantity:  [type: "Integer"]]]]
         * </pre>
         *
         * @param messages List of message Maps with "role" and "content" keys
         * @param schema   Moqui-style parameter schema Map describing the expected output structure
         * @return A Map matching the shape described by schema, populated with the model's response
         */
        Map generateStructured(List<Map> messages, Map schema);
    }
}
