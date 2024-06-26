/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.configuration;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.spi.Message;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class TestConfigurationFactory
{
    @Test
    public void testAnnotatedGettersThrows()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-value", "some value");
        properties.put("boolean-value", "true");
        assertInvalidConfig(
                properties,
                binder -> configBinder(binder).bindConfig(AnnotatedGetter.class),
                ".*getStringValue.* not a valid setter .*",
                ".*isBooleanValue.* not a valid setter .*");
    }

    @Test
    public void testAnnotatedSetters()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-value", "some value");
        properties.put("boolean-value", "true");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(AnnotatedSetter.class));
        AnnotatedSetter annotatedSetter = injector.getInstance(AnnotatedSetter.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
        assertThat(annotatedSetter).isNotNull();
        assertThat(annotatedSetter.getStringValue()).isEqualTo("some value");
        assertThat(annotatedSetter.isBooleanValue()).isTrue();
    }

    @Test
    public void testConfigurationDespiteLegacyConfig()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-a", "this is a");
        properties.put("string-b", "this is b");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(LegacyConfigPresent.class));
        LegacyConfigPresent legacyConfigPresent = injector.getInstance(LegacyConfigPresent.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
        assertThat(legacyConfigPresent).isNotNull();
        assertThat(legacyConfigPresent.getStringA()).isEqualTo("this is a");
        assertThat(legacyConfigPresent.getStringB()).isEqualTo("this is b");
    }

    @Test
    public void testConfigurationThroughLegacyConfig()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-value", "this is a");
        properties.put("string-b", "this is b");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(LegacyConfigPresent.class));
        LegacyConfigPresent legacyConfigPresent = injector.getInstance(LegacyConfigPresent.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(1);
        monitor.assertMatchingWarningRecorded("Configuration property 'string-value' has been replaced. Use 'string-a' instead.");
        assertThat(legacyConfigPresent).isNotNull();
        assertThat(legacyConfigPresent.getStringA()).isEqualTo("this is a");
        assertThat(legacyConfigPresent.getStringB()).isEqualTo("this is b");
    }

    @Test
    public void testConfigurationWithPrefixThroughLegacyConfig()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("example.string-value", "this is a");
        properties.put("example.string-b", "this is b");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(LegacyConfigPresent.class, "example"));
        LegacyConfigPresent legacyConfigPresent = injector.getInstance(LegacyConfigPresent.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(1);
        monitor.assertMatchingWarningRecorded("Configuration property 'example.string-value' has been replaced. Use 'example.string-a' instead.");
        assertThat(legacyConfigPresent).isNotNull();
        assertThat(legacyConfigPresent.getStringA()).isEqualTo("this is a");
        assertThat(legacyConfigPresent.getStringB()).isEqualTo("this is b");
    }

    @Test
    public void testConfigurationWithRedundantLegacyConfigThrows()
    {
        assertInvalidConfig(
                ImmutableMap.<String, String>builder()
                        .put("string-value", "this is a")
                        .put("string-a", "this is a")
                        .put("string-b", "this is b")
                        .build(),
                binder -> configBinder(binder).bindConfig(LegacyConfigPresent.class),
                ImmutableList.of(".*string-value.* conflicts with property 'string-a' .*"),
                ImmutableList.of(".*Configuration property 'string-value' has been replaced. Use 'string-a' instead."));
    }

    @Test
    public void testConfigurationWithConflictingLegacyConfigThrows()
    {
        assertInvalidConfig(
                ImmutableMap.<String, String>builder()
                        .put("string-value", "this is the old value")
                        .put("string-a", "this is a")
                        .put("string-b", "this is b")
                        .build(),
                binder -> configBinder(binder).bindConfig(LegacyConfigPresent.class),
                ImmutableList.of(".*string-value.* conflicts with property 'string-a' .*"),
                ImmutableList.of(".*Configuration property 'string-value' has been replaced. Use 'string-a' instead."));
    }

    @Test
    public void testConfigurationDespiteDeprecatedConfig()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-b", "this is b");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(DeprecatedConfigPresent.class));
        DeprecatedConfigPresent deprecatedConfigPresent = injector.getInstance(DeprecatedConfigPresent.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
        assertThat(deprecatedConfigPresent).isNotNull();
        assertThat(deprecatedConfigPresent.getStringA()).isEqualTo("defaultA");
        assertThat(deprecatedConfigPresent.getStringB()).isEqualTo("this is b");
    }

    @Test
    public void testConfigurationThroughDeprecatedConfig()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-a", "this is a");
        properties.put("string-b", "this is b");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(DeprecatedConfigPresent.class));
        DeprecatedConfigPresent deprecatedConfigPresent = injector.getInstance(DeprecatedConfigPresent.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(1);
        monitor.assertMatchingWarningRecorded("Configuration property 'string-a' is deprecated and should not be used");
        assertThat(deprecatedConfigPresent).isNotNull();
        assertThat(deprecatedConfigPresent.getStringA()).isEqualTo("this is a");
        assertThat(deprecatedConfigPresent.getStringB()).isEqualTo("this is b");
    }

    @Test
    public void testConfigurationWithPrefixThroughDeprecatedConfig()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("example.string-a", "this is a");
        properties.put("example.string-b", "this is b");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(DeprecatedConfigPresent.class, "example"));
        DeprecatedConfigPresent deprecatedConfigPresent = injector.getInstance(DeprecatedConfigPresent.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(1);
        monitor.assertMatchingWarningRecorded("Configuration property 'example.string-a' is deprecated and should not be used");
        assertThat(deprecatedConfigPresent).isNotNull();
        assertThat(deprecatedConfigPresent.getStringA()).isEqualTo("this is a");
        assertThat(deprecatedConfigPresent.getStringB()).isEqualTo("this is b");
    }

    @Test
    public void testDefunctPropertyInConfigThrows()
    {
        assertInvalidConfig(
                ImmutableMap.<String, String>builder()
                        .put("string-value", "this is a")
                        .put("defunct-value", "this shouldn't work")
                        .build(),
                binder -> configBinder(binder).bindConfig(DefunctConfigPresent.class),
                ".*Defunct property 'defunct-value'.*DefunctConfigPresent.*");
    }

    @Test
    public void testDefunctPropertyWithPrefixInConfigThrows()
    {
        assertInvalidConfig(
                ImmutableMap.<String, String>builder()
                        .put("example.string-value", "this is a")
                        .put("example.defunct-value", "this shouldn't work")
                        .build(),
                binder -> configBinder(binder).bindConfig(DefunctConfigPresent.class, "example"),
                ".*Defunct property 'example.defunct-value'.*DefunctConfigPresent.*");
    }

    @Test
    public void testSuccessfulBeanValidation()
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("string-value", "has a value");
        properties.put("int-value", "50");
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(BeanValidationClass.class));
        BeanValidationClass beanValidationClass = injector.getInstance(BeanValidationClass.class);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
        assertThat(beanValidationClass).isNotNull();
        assertThat(beanValidationClass.getStringValue()).isEqualTo("has a value");
        assertThat(beanValidationClass.getIntValue()).isEqualTo(50);
    }

    @Test
    public void testFailedBeanValidation()
    {
        assertInvalidConfig(
                ImmutableMap.of("int-value", "5000"), // out of range
                binder -> configBinder(binder).bindConfig(BeanValidationClass.class),
                ".*Invalid configuration property int-value: must be less than or equal to 100.*BeanValidationClass.*",
                ".*Invalid configuration property string-value: must not be null.*BeanValidationClass.*");
    }

    @Test
    public void testFailedBeanValidationPrefix()
    {
        assertInvalidConfig(
                ImmutableMap.of("example.int-value", "5000"), // out of range
                binder -> configBinder(binder).bindConfig(BeanValidationClass.class, "example"),
                ".*Invalid configuration property example.int-value: must be less than or equal to 100.*BeanValidationClass.*",
                ".*Invalid configuration property example.string-value: must not be null.*BeanValidationClass.*");
    }

    @Test
    public void testFailedCoercion()
    {
        assertInvalidConfig(
                ImmutableMap.of("int-value", "abc %s xyz"), // not an int
                binder -> configBinder(binder).bindConfig(BeanValidationClass.class),
                ".*Invalid value 'abc %s xyz' for type int \\(property 'int-value'\\).*BeanValidationClass.*");
    }

    @Test
    public void testAcceptBooleanValue()
    {
        for (String value : ImmutableList.of("true", "TRUE", "tRuE")) {
            Map<String, String> properties = new HashMap<>();
            properties.put("booleanOption", value);
            TestMonitor monitor = new TestMonitor();
            Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(Config1.class));
            Config1 config = requireNonNull(injector.getInstance(Config1.class), "injector.getInstance(Config1.class) is null");
            monitor.assertNumberOfErrors(0);
            monitor.assertNumberOfWarnings(0);
            assertThat(config.getBooleanOption()).isTrue();
        }

        for (String value : ImmutableList.of("false", "FALSE", "fAlsE")) {
            Map<String, String> properties = new HashMap<>();
            properties.put("booleanOption", value);
            TestMonitor monitor = new TestMonitor();
            Injector injector = createInjector(properties, monitor, binder -> configBinder(binder).bindConfig(Config1.class));
            Config1 config = requireNonNull(injector.getInstance(Config1.class), "injector.getInstance(Config1.class) is null");
            monitor.assertNumberOfErrors(0);
            monitor.assertNumberOfWarnings(0);
            assertThat(config.getBooleanOption()).isFalse();
        }
    }

    @Test
    public void testRejectUnknownBooleanValue()
    {
        for (String value : ImmutableList.of("yes", "no", "1", "0")) {
            assertInvalidConfig(
                    ImmutableMap.of("booleanOption", value),
                    binder -> configBinder(binder).bindConfig(Config1.class),
                    ".*Invalid value '" + value + "' for type boolean \\(property 'booleanOption'\\).*Config1.*");
        }
    }

    @Test
    public void testFailedCoercionPrefix()
    {
        assertInvalidConfig(
                ImmutableMap.of("example.int-value", "abc %s xyz"), // not an int
                binder -> configBinder(binder).bindConfig(BeanValidationClass.class, "example"),
                ".*Invalid value 'abc %s xyz' for type int \\(property 'example.int-value'\\).*BeanValidationClass.*");
    }

    @Test
    public void testFromString()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("value", "value-good-for-fromString"), monitor, binder -> configBinder(binder).bindConfig(FromStringClass.class));
        assertThat(injector.getInstance(FromStringClass.class).value)
                .isSameAs(FromStringClass.Value.FROM_STRING_VALUE);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);

        assertInvalidConfig(
                ImmutableMap.of("value", "value-good-for-valueOf"),
                binder -> configBinder(binder).bindConfig(FromStringClass.class),
                ".*Invalid value 'value-good-for-valueOf' for type.*\\(property 'value'\\).*FromStringClass.*");
    }

    @Test
    public void testEnumWithFromString()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("value", "yes"), monitor, binder -> configBinder(binder).bindConfig(EnumWithFromStringClass.class));
        assertThat(injector.getInstance(EnumWithFromStringClass.class).value)
                .isSameAs(EnumWithFromStringClass.Value.TRUE);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);

        assertInvalidConfig(
                ImmutableMap.of("value", "TRUE"),
                binder -> configBinder(binder).bindConfig(EnumWithFromStringClass.class),
                ".*Invalid value 'TRUE' for type.*\\(property 'value'\\).*EnumWithFromStringClass.*");
    }

    @Test
    public void testEnum()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("value", "value"), monitor, binder -> configBinder(binder).bindConfig(EnumClass.class));
        assertThat(injector.getInstance(EnumClass.class).value).isSameAs(EnumClass.Value.VALUE);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
    }

    @Test
    public void testEnumValueWithUnderscores()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("value", "value_with_underscores"), monitor, binder -> configBinder(binder).bindConfig(EnumClass.class));
        assertThat(injector.getInstance(EnumClass.class).value)
                .isSameAs(EnumClass.Value.VALUE_WITH_UNDERSCORES);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
    }

    @Test
    public void testEnumValueWithMinusesInsteadOfUnderscores()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("value", "value-with-underscores"), monitor, binder -> configBinder(binder).bindConfig(EnumClass.class));
        assertThat(injector.getInstance(EnumClass.class).value)
                .isSameAs(EnumClass.Value.VALUE_WITH_UNDERSCORES);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
    }

    @Test
    public void testInvalidEnumValue()
    {
        assertInvalidConfig(
                ImmutableMap.of("value", "invalid value"),
                binder -> configBinder(binder).bindConfig(EnumWithFromStringClass.class),
                ".*Invalid value 'invalid value' for type.*\\(property 'value'\\).*EnumWithFromStringClass.*");
    }

    @Test
    public void testListOfStrings()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("values", "ala, ma ,kota, "), monitor, binder -> configBinder(binder).bindConfig(ListOfStringsClass.class));
        assertThat(injector.getInstance(ListOfStringsClass.class).getValues()).isEqualTo(ImmutableList.of("ala", "ma", "kota"));
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);
    }

    @Test
    public void testValueOf()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("value", "value-good-for-valueOf"), monitor, binder -> configBinder(binder).bindConfig(ValueOfClass.class));
        assertThat(injector.getInstance(ValueOfClass.class).value).isSameAs(ValueOfClass.Value.VALUE_OF_VALUE);
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);

        assertInvalidConfig(
                ImmutableMap.of("value", "anything"),
                binder -> configBinder(binder).bindConfig(ValueOfClass.class),
                ".*Invalid value 'anything' for type.*\\(property 'value'\\).*ValueOfClass.*");
    }

    @Test
    public void testStringConstructor()
    {
        TestMonitor monitor = new TestMonitor();
        Injector injector = createInjector(ImmutableMap.of("value", "constructor-value"), monitor, binder -> configBinder(binder).bindConfig(StringConstructorClass.class));
        assertThat(injector.getInstance(StringConstructorClass.class).value.string).isEqualTo("constructor-argument: constructor-value");
        monitor.assertNumberOfErrors(0);
        monitor.assertNumberOfWarnings(0);

        assertInvalidConfig(
                ImmutableMap.of("value", "bad-value"),
                binder -> configBinder(binder).bindConfig(StringConstructorClass.class),
                ".*Invalid value 'bad-value' for type.*\\(property 'value'\\).*StringConstructorClass.*");
    }

    @Test
    public void testUsedProperties()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-value", "some value");
        properties.put("boolean-value", "true");
        properties.put("unused", "unused");

        ConfigurationFactory configurationFactory = new ConfigurationFactory(properties, null, new TestMonitor());
        configurationFactory.registerConfigurationClasses(ImmutableList.of(binder -> configBinder(binder).bindConfig(AnnotatedSetter.class)));
        configurationFactory.validateRegisteredConfigurationProvider();
        assertThat(configurationFactory.getUsedProperties()).hasSameElementsAs(ImmutableSet.of("string-value", "boolean-value"));
    }

    @Test
    public void testUsedPropertiesWithFailure()
    {
        Map<String, String> properties = new TreeMap<>();
        properties.put("string-value", "some value");
        properties.put("boolean-value", "invalid");

        ConfigurationFactory configurationFactory = new ConfigurationFactory(properties, null, new TestMonitor());
        configurationFactory.registerConfigurationClasses(ImmutableList.of(binder -> configBinder(binder).bindConfig(AnnotatedSetter.class)));
        List<Message> messages = configurationFactory.validateRegisteredConfigurationProvider();
        assertMessagesMatch(messages, ImmutableList.of(".*Invalid value 'invalid' for type boolean \\(property 'boolean-value'\\).*AnnotatedSetter.*"));
        assertThat(configurationFactory.getUsedProperties()).isEqualTo(properties.keySet());
    }

    private static Injector createInjector(Map<String, String> properties, TestMonitor monitor, Module module)
    {
        ConfigurationFactory configurationFactory = new ConfigurationFactory(properties, null, monitor);
        configurationFactory.registerConfigurationClasses(ImmutableList.of(module));
        List<Message> messages = configurationFactory.validateRegisteredConfigurationProvider();
        assertThat(configurationFactory.getUsedProperties()).hasSameElementsAs(properties.keySet());
        return Guice.createInjector(new ConfigurationModule(configurationFactory), module, new ValidationErrorModule(messages));
    }

    private static void assertInvalidConfig(Map<String, String> properties, Module module, String... expectedErrorMessagePatterns)
    {
        assertInvalidConfig(properties, module, ImmutableList.copyOf(expectedErrorMessagePatterns), ImmutableList.of());
    }

    private static void assertInvalidConfig(Map<String, String> properties, Module module, List<String> expectedErrorMessagePatterns, List<String> expectedWarningMessagePatterns)
    {
        TestMonitor monitor = new TestMonitor();

        ConfigurationFactory configurationFactory = new ConfigurationFactory(properties, null, monitor);
        configurationFactory.registerConfigurationClasses(ImmutableList.of(module));

        List<Message> messages = configurationFactory.validateRegisteredConfigurationProvider();
        assertMessagesMatch(monitor.getErrors(), expectedErrorMessagePatterns);
        assertMessagesMatch(monitor.getWarnings(), expectedWarningMessagePatterns);

        try {
            Guice.createInjector(new ConfigurationModule(configurationFactory), module, new ValidationErrorModule(messages));
            fail("Expected an exception in object creation due to conflicting configuration");
        }
        catch (CreationException e) {
            for (String expectedErrorMessagePattern : expectedErrorMessagePatterns) {
                e.getMessage().matches(expectedErrorMessagePattern);
            }
            assertMessagesMatch(
                    e.getErrorMessages(),
                    ImmutableList.<String>builder()
                            .addAll(expectedErrorMessagePatterns)
                            .addAll(expectedWarningMessagePatterns)
                            .build());
        }
    }

    private static void assertMessagesMatch(Collection<? extends Object> messages, List<String> expectedErrorMessagePatterns)
    {
        assertThat(messages.size()).as("expected error count").isEqualTo(expectedErrorMessagePatterns.size());

        List<Pattern> patterns = expectedErrorMessagePatterns.stream()
                .map(Pattern::compile)
                .collect(Collectors.toList());

        Map<Pattern, String> usedPatterns = new HashMap<>();
        for (Object message : messages) {
            String messageString = message.toString();
            Pattern matchedPattern = null;
            for (Pattern pattern : patterns) {
                if (pattern.matcher(messageString).matches()) {
                    assertThat(matchedPattern).as(format("Error message matches two patterns patterns:\nmessage:\n  %s\npatterns:\n  %s\n  %s", messageString, matchedPattern, pattern)).isNull();
                    String usedMatch = usedPatterns.put(pattern, messageString);
                    assertThat(usedMatch).as(format("Pattern '%s' matches message '%s' and '%s", pattern, messageString, usedMatch)).isNull();
                    matchedPattern = pattern;
                }
            }
            assertThat(matchedPattern).as(format("Error message did not match any expected patterns:\nmessage:\n  %s\npatterns:\n  %s", messageString, Joiner.on("\n  ").join(patterns))).isNotNull();
        }
    }

    @SuppressWarnings("unused")
    public static class AnnotatedGetter
    {
        private String stringValue;
        private boolean booleanValue;

        @Config("string-value")
        public String getStringValue()
        {
            return stringValue;
        }

        public void setStringValue(String stringValue)
        {
            this.stringValue = stringValue;
        }

        @Config("boolean-value")
        public boolean isBooleanValue()
        {
            return booleanValue;
        }

        public void setBooleanValue(boolean booleanValue)
        {
            this.booleanValue = booleanValue;
        }
    }

    public static class AnnotatedSetter
    {
        private String stringValue;
        private boolean booleanValue;

        public String getStringValue()
        {
            return stringValue;
        }

        @Config("string-value")
        public void setStringValue(String stringValue)
        {
            this.stringValue = stringValue;
        }

        public boolean isBooleanValue()
        {
            return booleanValue;
        }

        @Config("boolean-value")
        public void setBooleanValue(boolean booleanValue)
        {
            this.booleanValue = booleanValue;
        }
    }

    public static class LegacyConfigPresent
    {
        private String stringA = "defaultA";
        private String stringB = "defaultB";

        public String getStringA()
        {
            return stringA;
        }

        @Config("string-a")
        @LegacyConfig("string-value")
        public void setStringA(String stringValue)
        {
            this.stringA = stringValue;
        }

        public String getStringB()
        {
            return stringB;
        }

        @Config("string-b")
        public void setStringB(String stringValue)
        {
            this.stringB = stringValue;
        }
    }

    public static class DeprecatedConfigPresent
    {
        private String stringA = "defaultA";
        private String stringB = "defaultB";

        @Deprecated
        public String getStringA()
        {
            return stringA;
        }

        @Deprecated
        @Config("string-a")
        public void setStringA(String stringValue)
        {
            this.stringA = stringValue;
        }

        public String getStringB()
        {
            return stringB;
        }

        @Config("string-b")
        public void setStringB(String stringValue)
        {
            this.stringB = stringValue;
        }
    }

    @SuppressWarnings("unused")
    @DefunctConfig("defunct-value")
    public static class DefunctConfigPresent
    {
        private String stringValue;
        private boolean booleanValue;

        public String getStringValue()
        {
            return stringValue;
        }

        @Config("string-value")
        public void setStringValue(String stringValue)
        {
            this.stringValue = stringValue;
        }
    }

    public static class BeanValidationClass
    {
        @NotNull
        private String stringValue;

        private int myIntValue;

        public String getStringValue()
        {
            return stringValue;
        }

        @Config("string-value")
        public void setStringValue(String value)
        {
            this.stringValue = value;
        }

        @Min(1)
        @Max(100)
        public int getIntValue()
        {
            return myIntValue;
        }

        @Config("int-value")
        public void setIntValue(int value)
        {
            this.myIntValue = value;
        }
    }

    public static class FromStringClass
    {
        public static class Value
        {
            static final Value FROM_STRING_VALUE = new Value("");

            public static Value fromString(String value)
            {
                checkArgument("value-good-for-fromString".equals(value));
                return FROM_STRING_VALUE;
            }

            public static Value valueOf(String value)
            {
                checkArgument("value-good-for-valueOf".equals(value));
                return new Value("");
            }

            public Value(String ignored) {}
        }

        private Value value;

        public Value getValue()
        {
            return value;
        }

        @Config("value")
        public void setValue(Value value)
        {
            this.value = value;
        }
    }

    public static class EnumWithFromStringClass
    {
        public enum Value
        {
            TRUE, FALSE;

            public static Value fromString(String string)
            {
                switch (requireNonNull(string, "string is null")) {
                    case "yes":
                        return TRUE;
                    case "no":
                        return FALSE;
                    default:
                        throw new IllegalArgumentException("Invalid value: " + string);
                }
            }
        }

        private Value value;

        public Value getValue()
        {
            return value;
        }

        @Config("value")
        public void setValue(Value value)
        {
            this.value = value;
        }
    }

    public static class EnumClass
    {
        public enum Value
        {
            VALUE, VALUE_WITH_UNDERSCORES;
        }

        private Value value;

        public Value getValue()
        {
            return value;
        }

        @Config("value")
        public void setValue(Value value)
        {
            this.value = value;
        }
    }

    public static class ListOfStringsClass
    {
        private List<String> values;

        public List<String> getValues()
        {
            return values;
        }

        @Config("values")
        public void setValues(List<String> values)
        {
            this.values = values;
        }
    }

    public static class ValueOfClass
    {
        public static class Value
        {
            static final Value VALUE_OF_VALUE = new Value("");

            public static Value valueOf(String value)
            {
                checkArgument("value-good-for-valueOf".equals(value));
                return VALUE_OF_VALUE;
            }

            public Value(String ignored) {}
        }

        private Value value;

        public Value getValue()
        {
            return value;
        }

        @Config("value")
        public void setValue(Value value)
        {
            this.value = value;
        }
    }

    public static class StringConstructorClass
    {
        public static class Value
        {
            private final String string;

            public Value(String string)
            {
                checkArgument(!"bad-value".equals(string));
                this.string = "constructor-argument: " + requireNonNull(string, "string is null");
            }
        }

        private Value value;

        public Value getValue()
        {
            return value;
        }

        @Config("value")
        public void setValue(Value value)
        {
            this.value = value;
        }
    }
}
