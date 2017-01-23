package edu.umd.lib.exception;

/***
 * Custom Exception to check if the request is valid request from WuFoo
 */
public class BoxCustomException extends Exception {

  private static final long serialVersionUID = 1L;

  public BoxCustomException() {
  }

  // Constructor that accepts a message
  public BoxCustomException(String message) {
    super(message);
  }
}
