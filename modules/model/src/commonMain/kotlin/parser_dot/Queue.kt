package parser_dot

/** First in, first out: tokens on their way to the parser, parse-log entries in file order. */
typealias Queue<T> = ArrayDeque<T>
