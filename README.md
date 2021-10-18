# Philter NiFi

This project contains an Apache NiFi to interact with [Philter](https://www.mtnfog.com/products/philter/) for redacting PHI and PII from text.

## Build and Usage

Clone this repository and run `mvn clean install`. Copy the built `nar` file into your Apache NiFi `lib` directory and restart NiFi. The Philter processor will now be available for use in your data flows.

A running instance of [Philter](https://www.mtnfog.com/products/philter/) is required to use the Apache NiFi processor. Set the location of the Philter instance in the processor's settings after adding it to a data flow. 

This processor utilizes the [Philter Java SDK](https://github.com/mtnfog/philter-sdk-java).

## License

This project is licensed under the Apache license, version 2.0.