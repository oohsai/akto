import React, { useState, useEffect } from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faEdit, faCheckSquare } from '@fortawesome/free-regular-svg-icons'

import InputBase from "@mui/material/TextField"
import IconButton from "@mui/material/IconButton"
import TextFieldCloseable from './TextFieldCloseable.jsx'

const TemplateStringEditor = ({ defaultText, onChange, usePureJs = false, storageKey }) => {
  const [toggle, setToggle] = useState(true);
  const [text, setText] = useState(defaultText);

  // Load auto-saved data when the component mounts
  useEffect(() => {
    const savedData = localStorage.getItem(storageKey);
    if (savedData) {
      setText(savedData);
    }
  }, [storageKey]);

  const toggleChecked = () => {
    if (!toggle) {
      // Save the data to localStorage when switching back to read mode
      localStorage.setItem(storageKey, text);
      onChange(text);
    }
    setToggle((toggle) => !toggle);
  };

  const onChangeInputBase = (e) => {
    const newText = e.target.value;
    setText(newText);
  };

  return (
    <div style={{ position: "relative" }}>
      {toggle && <TextFieldCloseable text={text} usePureJs={usePureJs} />}
      {!toggle && (
        <InputBase
          value={text}
          onChange={onChangeInputBase}
          fullWidth
          multiline
          inputProps={{ className: 'request-editor' }}
          variant="standard"
        />
      )}
      <div style={{ position: "absolute", top: "4px", right: "10px" }}>
        <IconButton onClick={toggleChecked}>
          <FontAwesomeIcon icon={toggle ? faEdit : faCheckSquare} className="primary-btn" />
        </IconButton>
      </div>
    </div>
  );
};

export default TemplateStringEditor;
