# Philter NiFi

This project contains an Apache NiFi to interact with Philter for redacting PHI and PII from text.

## Build and Usage

Clone this repository and run `mvn clean install`. Copy the built `nar` file into your Apache NiFi `lib` directory and restart NiFi. The Philter processor will now be available for use in your data flows.

## License

This project is licensed under the Apache license, version 2.0.