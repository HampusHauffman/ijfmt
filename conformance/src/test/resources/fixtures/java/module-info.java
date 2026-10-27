/**
 * Module descriptor.
 */
module com.example.app {
    requires java.base;
    requires transitive java.sql;
    requires static org.jetbrains.annotations;
    exports com.example;
    exports com.example.internal to com.example.tests, com.example.tools;
    opens com.example.model;
    uses com.example.spi.Plugin;
    provides com.example.spi.Plugin with com.example.DefaultPlugin, com.example.OtherPlugin;
}
